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

		when(executionQueue.requeue(ABANDONED)).thenReturn(true);

		assertThat(reclaim(true).reclaimAbandoned()).isEqualTo(1);

		verify(executionQueue).requeue(ABANDONED);
		verify(executionProgressService, never()).interruptAbandoned(any(), any());
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

		verify(executionProgressService).interruptAbandoned(any(), any());
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

		verify(executionProgressService).interruptAbandoned(any(), any());
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

		verify(executionProgressService).failAbandoned(any(), any());
		verify(executionQueue, never()).requeue(anyLong());
		verify(executionProgressService, never()).interruptAbandoned(any(), any());
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

		verify(executionProgressService).rejectSuperseded(any(), any());
		verify(executionProgressService, never()).interruptAbandoned(any(), any());
	}

	/**
	 * Counted as nothing, because nothing was recovered. The number this returns
	 * is what the log says out loud, and it went from "rows we looked at" to "rows
	 * we took back" when recovery started running on a timer: a pass that finds a
	 * completion got there first has not reclaimed anything, and saying it had
	 * would report work that never happened, every interval, forever.
	 */
	@Test
	void ignoresARowThatIsNoLongerThere() {
		when(executionQueue.expiredLeases()).thenReturn(List.of(ABANDONED));
		when(executionRepository.findById(ABANDONED)).thenReturn(Optional.empty());

		assertThat(reclaim(true).reclaimAbandoned()).isZero();

		verify(executionQueue, never()).requeue(anyLong());
		verify(executionProgressService, never()).interruptAbandoned(any(), any());
	}

	/**
	 * The row stopped being the abandoned one between the reading and the write -
	 * it completed, it was cancelled, its owner started renewing again, or another
	 * reclaimer took it. The conditional write says so by changing nothing, and
	 * nothing is exactly what this pass may then do: an outcome somebody else
	 * reached is not ours to overwrite.
	 */
	@Test
	void writesNothingWhenTheRowStoppedBeingAbandonedBeforeItCouldBeClosed() {
		abandoned(ExecutionType.INVENTORY, 1);

		when(executionProgressService.failAbandoned(any(), any())).thenReturn(false);
		when(executionProgressService.interruptAbandoned(any(), any())).thenReturn(false);

		assertThat(reclaim(false).reclaimAbandoned()).isZero();

		// The frontier was asked and said no; what must not happen is anything
		// derived from a win - the reconcile this transition would otherwise queue.
		verify(executionEnqueueService, never()).enqueue(any());
	}

	/**
	 * The same for the poison job: the sentence is written only by whoever won the
	 * row, so two passes cannot both record that it ran out of attempts.
	 */
	@Test
	void doesNotFailAnExhaustedExecutionAnotherPassAlreadyClosed() {
		abandoned(ExecutionType.INVENTORY, WorkerProperties.DEFAULT_MAX_CLAIMS);

		when(executionProgressService.failAbandoned(any(), any())).thenReturn(false);
		when(executionProgressService.interruptAbandoned(any(), any())).thenReturn(false);

		assertThat(reclaim(true).reclaimAbandoned()).isZero();
	}

	/**
	 * A requeue that changed nothing is not evidence of a successor, and a
	 * confirmed successor is not evidence that the row is still there to refuse.
	 * Both have to hold, which is what keeps a completion from being recorded as
	 * superseded.
	 */
	@Test
	void doesNotRefuseAnExecutionThatIsNoLongerRunningEvenWithASuccessorWaiting() {
		abandoned(ExecutionType.INVENTORY, 1);

		when(executionQueue.requeue(ABANDONED)).thenReturn(false);
		when(executionQueue.hasWaitingDuplicate(ABANDONED)).thenReturn(true);
		when(executionProgressService.rejectSuperseded(any(), any())).thenReturn(false);

		assertThat(reclaim(true).reclaimAbandoned()).isZero();
	}

	/**
	 * And the older half of the same rule, unchanged: with no successor confirmed,
	 * a requeue that changed nothing means the row moved on, never that it was
	 * superseded.
	 */
	@Test
	void doesNotRefuseAnExecutionWhoseRequeueFoundNoSuccessor() {
		abandoned(ExecutionType.INVENTORY, 1);

		when(executionQueue.requeue(ABANDONED)).thenReturn(false);
		when(executionQueue.hasWaitingDuplicate(ABANDONED)).thenReturn(false);

		assertThat(reclaim(true).reclaimAbandoned()).isZero();

		verify(executionProgressService, never()).rejectSuperseded(any(), any());
		verify(executionProgressService, never()).rejectSuperseded(any(), any());
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

		// The row is still the abandoned one when the write lands, which is the
		// ordinary case; the tests below that are about losing that race say so.
		when(executionProgressService.failAbandoned(any(), any())).thenReturn(true);
		when(executionProgressService.interruptAbandoned(any(), any())).thenReturn(true);
		when(executionProgressService.rejectSuperseded(any(), any())).thenReturn(true);
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