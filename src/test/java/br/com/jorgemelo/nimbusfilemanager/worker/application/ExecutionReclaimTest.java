package br.com.jorgemelo.nimbusfilemanager.worker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueue;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * Taking back what a dead worker left.
 *
 * <p>
 * The question asked is the narrow one - which leases expired - and that is the
 * point of the class. Asking the wide one, which executions nobody holds a lease
 * on, would sweep up every run of the engine that has not been migrated yet:
 * those never had a lease, they belong to the application that is running them
 * at this moment, and requeueing one would be the same work happening twice over
 * the same files.
 */
class ExecutionReclaimTest {

	private static final long ABANDONED = 42L;

	@TempDir
	private Path tempDir;

	private final ExecutionQueue executionQueue = mock(ExecutionQueue.class);
	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
	private final ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);
	private final ExecutionEnqueueService executionEnqueueService = mock(ExecutionEnqueueService.class);
	private final ExecutionJobHandler handler = JobHandlerMock.answeringItsOwnDefaults();

	@Test
	void asksOnlyAboutLeasesThatExpired() {
		when(executionQueue.expiredLeases()).thenReturn(List.of());

		assertThat(reclaim(true).reclaimAbandoned()).isZero();

	}

	@Test
	void putsAResumableExecutionBackOnTheQueue() {
		abandoned(ExecutionType.INVENTORY, 1);

		assertThat(reclaim(true).reclaimAbandoned()).isEqualTo(1);

		verify(executionQueue).requeue(ABANDONED);
		verify(executionProgressService, never()).interrupt(any(), any());
	}

	/**
	 * Half of a move already happened, and a second run would begin from a world
	 * the first one changed - so it is closed, and the divergence left behind is
	 * what reconciliation is for.
	 */
	@Test
	void closesAnExecutionThatCannotSimplyBeRunAgain() {
		abandoned(ExecutionType.INVENTORY, 1);

		assertThat(reclaim(false).reclaimAbandoned()).isEqualTo(1);

		verify(executionProgressService).interrupt(any(), any());
		verify(executionQueue, never()).requeue(anyLong());
	}

	/**
	 * A type this worker has no handler for is one it cannot judge, and the answer
	 * that cannot corrupt anything is the one it gives.
	 */
	@Test
	void closesAnExecutionOfATypeItCannotJudge() {
		abandoned(ExecutionType.CONVERSION, 1);

		assertThat(reclaim(true).reclaimAbandoned()).isEqualTo(1);

		verify(executionProgressService).interrupt(any(), any());
		verify(executionQueue, never()).requeue(anyLong());
	}

	/**
	 * The poison job. Requeueing it would requeue it invisible - the claim filters
	 * on the attempt budget - and a row waiting forever for a worker that is not
	 * allowed to take it is the quietest kind of lost work. It ends instead, in the
	 * history, where somebody can see it.
	 */
	@Test
	void endsAnExecutionThatHasNoAttemptsLeftInsteadOfQueueingItForever() {
		abandoned(ExecutionType.INVENTORY, WorkerProperties.DEFAULT_MAX_CLAIMS);

		assertThat(reclaim(true).reclaimAbandoned()).isEqualTo(1);

		verify(executionProgressService).fail(any(), any());
		verify(executionQueue, never()).requeue(anyLong());
		verify(executionProgressService, never()).interrupt(any(), any());
	}

	/**
	 * Putting it back would mean two of the same waiting, which the queue forbids
	 * and which would add nothing anyway. Refused rather than cancelled: nobody
	 * asked for this to stop.
	 */
	@Test
	void refusesAnAbandonedExecutionWhoseSuccessorIsAlreadyWaiting() {
		abandoned(ExecutionType.INVENTORY, 1);

		when(executionQueue.requeue(ABANDONED)).thenReturn(false);
		when(executionQueue.hasWaitingDuplicate(ABANDONED)).thenReturn(true);

		reclaim(true).reclaimAbandoned();

		verify(executionProgressService).reject(any(), any());
		verify(executionProgressService, never()).interrupt(any(), any());
	}

	@Test
	void ignoresARowThatIsNoLongerThere() {
		when(executionQueue.expiredLeases()).thenReturn(List.of(ABANDONED));
		when(executionRepository.findById(ABANDONED)).thenReturn(Optional.empty());

		assertThat(reclaim(true).reclaimAbandoned()).isEqualTo(1);

		verify(executionQueue, never()).requeue(anyLong());
		verify(executionProgressService, never()).interrupt(any(), any());
	}

	/**
	 * Closing the row records that the run stopped; it does not repair what the run
	 * had already done. A move that reached the disk in the moment before the
	 * process went leaves a file the catalog has never heard of, and the only thing
	 * that finds it is a reconcile.
	 *
	 * <p>
	 * Both folders, because the divergence looks different at each end: at the
	 * source a catalogued file is no longer where the catalog says it is, at the
	 * target a file exists that nobody catalogued.
	 */
	@Test
	void queuesAReconcileOfBothEndsOfWhatTheAbandonedRunWasMoving() throws IOException {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path target = Files.createDirectory(tempDir.resolve("target"));

		abandoned(ExecutionType.ORGANIZATION, 1, source, target);

		reclaim(false).reclaimAbandoned();

		ArgumentCaptor<Execution> captor = ArgumentCaptor.forClass(Execution.class);

		verify(executionEnqueueService, times(2)).enqueue(captor.capture());

		assertThat(captor.getAllValues()).allSatisfy(queued -> assertThat(queued.getExecutionType())
				.isEqualTo(ExecutionType.RECONCILE)).extracting(Execution::getSourcePath)
				.containsExactlyInAnyOrder(source.toString(), target.toString());
	}

	/**
	 * The commands the Files screen queues name one file, and by the time anybody
	 * reclaims them that file is often exactly what is no longer there. A reconcile
	 * asks its question about a folder, so what gets queued is the folder the
	 * divergence is in - asking about the file itself would fail on a path that is
	 * not a directory.
	 */
	@Test
	void queuesAReconcileOfTheFolderWhenWhatItWasTouchingWasAFile() throws IOException {
		Path folder = Files.createDirectory(tempDir.resolve("album"));

		abandoned(ExecutionType.EXPLORER_RENAME, 1, folder.resolve("gone.jpg"), folder.resolve("renamed.jpg"));

		reclaim(false).reclaimAbandoned();

		ArgumentCaptor<Execution> captor = ArgumentCaptor.forClass(Execution.class);

		verify(executionEnqueueService).enqueue(captor.capture());

		assertThat(captor.getValue().getSourcePath()).isEqualTo(folder.toString());
	}

	/**
	 * A pass that reads goes back on the queue and will do its own work again, so
	 * there is nothing to reconcile and nothing to queue.
	 */
	@Test
	void queuesNoReconcileForSomethingThatSimplyRunsAgain() {
		abandoned(ExecutionType.INVENTORY, 1, tempDir.resolve("source"), null);

		reclaim(true).reclaimAbandoned();

		verify(executionEnqueueService, never()).enqueue(any());
	}

	private void abandoned(ExecutionType type, int claimCount) {
		abandoned(type, claimCount, null, null);
	}

	private void abandoned(ExecutionType type, int claimCount, Path source, Path target) {
		Execution execution = Execution.builder().executionType(type).status(ExecutionStatus.RUNNING)
				.claimCount(claimCount).sourcePath(source == null ? null : source.toString())
				.targetPath(target == null ? null : target.toString()).build();

		execution.setId(ABANDONED);

		when(executionQueue.expiredLeases()).thenReturn(List.of(ABANDONED));
		when(executionRepository.findById(ABANDONED)).thenReturn(Optional.of(execution));
	}

	private ExecutionReclaim reclaim(boolean resumable) {
		when(handler.type()).thenReturn(ExecutionType.INVENTORY);
		when(handler.resumable()).thenReturn(resumable);

		return new ExecutionReclaim(executionQueue, executionRepository, executionProgressService,
				executionEnqueueService, new WorkerProperties(null, null, null, null, null, null, null, null, null,
						null),
				List.of(handler));
	}
}