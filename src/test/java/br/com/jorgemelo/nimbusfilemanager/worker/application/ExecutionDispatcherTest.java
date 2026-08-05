package br.com.jorgemelo.nimbusfilemanager.worker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OwnershipLostException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueue;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * The order in which a job is taken, and what happens when a step refuses.
 *
 * <p>
 * Most of what this asserts is invariant rather than behaviour: that the
 * attempt is counted only after the locks are held, that nothing of the domain
 * runs before that count is persisted, and that a busy tree costs no attempt.
 * Each of those is a sentence in the design that would be silently untrue if
 * the steps were reordered.
 */
class ExecutionDispatcherTest {

	private static final String TYPE = ExecutionType.INVENTORY.name();

	private final ExecutionQueue executionQueue = mock(ExecutionQueue.class);
	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
	private final ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);
	private final OperationLockService operationLockService = mock(OperationLockService.class);
	private final LeaseRenewer leaseRenewer = mock(LeaseRenewer.class);
	private final ExecutionJobHandler handler = JobHandlerMock.answeringItsOwnDefaults();

	private ExecutionDispatcher dispatcher;

	private Execution execution;

	private ExecutionOwnership ownership;

	@BeforeEach
	void setUp() {
		when(handler.type()).thenReturn(ExecutionType.INVENTORY);

		ownership = owning();

		when(operationLockService.acquireFor(anyLong(), any(), any(Path[].class))).thenReturn(ownership);

		execution = Execution.builder().executionType(ExecutionType.INVENTORY).status(ExecutionStatus.RUNNING).build();
		execution.setId(7L);

		when(executionRepository.findById(7L)).thenReturn(Optional.of(execution));

		dispatcher = new ExecutionDispatcher(executionQueue, executionRepository, executionProgressService,
				operationLockService, leaseRenewer, new WorkerProperties(null, null, null, null, null, null, null, null,
						null, null),
				new CategoryConcurrency(List.of(handler)), List.of(handler), new WorkerIdentity());
	}

	@Test
	void reportsThereWasNothingToDoWhenTheQueueIsEmpty() {
		when(executionQueue.reserve(anyString(), any(), anyInt(), anyInt())).thenReturn(Optional.empty());

		assertThat(dispatcher.dispatchOne()).isFalse();

		verify(leaseRenewer, never()).hold(any());
		verify(handler, never()).handle(any(), any(), any());
	}

	/**
	 * The invariant that matters most: locks first, then the attempt is counted,
	 * and only then does anything of the domain run.
	 */
	@Test
	void locksThenCountsTheAttemptThenRuns(@TempDir Path folder) {
		reserved(folder);

		when(executionQueue.countAttempt(eq(7L), anyString(), anyInt())).thenReturn(true);

		assertThat(dispatcher.dispatchOne()).isTrue();

		InOrder order = inOrder(operationLockService, executionQueue, handler);

		order.verify(operationLockService).acquireFor(eq(7L), eq(ExecutionType.INVENTORY), any(Path[].class));
		order.verify(executionQueue).countAttempt(eq(7L), anyString(), anyInt());
		order.verify(handler).handle(eq(execution), eq(reservedExecution(folder)), any());
	}

	@Test
	void holdsTheLeaseWhileItRunsAndReleasesItAfterwards(@TempDir Path folder) {
		reserved(folder);

		when(executionQueue.countAttempt(eq(7L), anyString(), anyInt())).thenReturn(true);

		dispatcher.dispatchOne();

		verify(leaseRenewer).hold(argThat(held -> held.executionId() == 7L));
		verify(leaseRenewer).release(7L);
	}

	/**
	 * A busy tree is contention, not a failed attempt: the execution goes back to
	 * the queue with its budget intact, or a folder someone else is using would
	 * eventually exhaust it.
	 */
	@Test
	void handsTheExecutionBackWithoutSpendingAnAttemptWhenTheTreeIsBusy(@TempDir Path folder) {
		reserved(folder);

		when(operationLockService.acquireFor(anyLong(), any(), any(Path[].class)))
				.thenThrow(new OperationLockException("busy"));

		assertThat(dispatcher.dispatchOne()).isTrue();

		verify(executionQueue).release(eq(7L), anyString(), anyInt());
		verify(executionQueue, never()).countAttempt(anyLong(), anyString(), anyInt());
		verify(handler, never()).handle(any(), any(), any());
	}

	/**
	 * The other half of that rule, and the bug that taught it. A failure before
	 * the count which is <em>not</em> contention used to escape the dispatcher
	 * altogether: the row stayed RUNNING with a counter of zero, its lease lapsed,
	 * recovery put it back and it threw again - forever, because the brake reads
	 * exactly the counter that never moved. So the attempt is charged first, and
	 * the failure is then treated like any other.
	 */
	@Test
	void chargesTheAttemptWhenSomethingOtherThanContentionFailsBeforeItRuns(@TempDir Path folder) {
		reserved(folder);

		when(operationLockService.acquireFor(anyLong(), any(), any(Path[].class)))
				.thenThrow(new IllegalStateException("the lock session could not be opened"));

		dispatcher.dispatchOne();

		verify(executionQueue).countAttempt(eq(7L), anyString(), anyInt());
		verify(executionProgressService).fail(eq(execution), any());
	}

	/**
	 * Charged, and still retried while the budget allows. A pool with no
	 * connection to spare describes the moment rather than the work, so it goes
	 * back on the queue - it simply cannot go back an unlimited number of times.
	 */
	@Test
	void putsItBackWhenWhatFailedBeforeTheCountWasThePassingKind(@TempDir Path folder) {
		reserved(folder);

		when(operationLockService.acquireFor(anyLong(), any(), any(Path[].class)))
				.thenThrow(new DataAccessResourceFailureException("no connection to spare"));

		dispatcher.dispatchOne();

		verify(executionQueue).countAttempt(eq(7L), anyString(), anyInt());
		verify(executionQueue).release(eq(7L), anyString(), anyInt());
		verify(executionProgressService, never()).fail(any(), any());
	}

	/**
	 * The row can go between the claim and the failure being written - a purge, a
	 * restore. The attempt is charged all the same, because the counter is the
	 * brake, but there is nothing left to write an outcome on.
	 */
	@Test
	void chargesTheAttemptAndWritesNothingWhenTheRowWentBeforeItCouldBeTold(@TempDir Path folder) {
		reserved(folder);

		when(operationLockService.acquireFor(anyLong(), any(), any(Path[].class)))
				.thenThrow(new IllegalStateException("the lock session could not be opened"));
		when(executionRepository.findById(7L)).thenReturn(Optional.empty());

		dispatcher.dispatchOne();

		verify(executionQueue).countAttempt(eq(7L), anyString(), anyInt());
		verify(executionProgressService, never()).fail(any(), any());
	}

	/**
	 * A type whose work is a place on disk, on a row that names none. There is
	 * nothing to take the locks over, and running without them is the one thing the
	 * exclusion exists to prevent - so it ends here, said out loud, rather than
	 * escaping before the attempt was counted and being reclaimed and re-thrown for
	 * as long as anybody keeps a worker running.
	 */
	@Test
	void refusesToRunATypeThatNeedsAPathAndNamesNone() {
		when(executionQueue.reserve(anyString(), any(), anyInt(), anyInt()))
				.thenReturn(Optional.of(new ClaimedExecution(7L, TYPE, null, null, null)));

		dispatcher.dispatchOne();

		verify(executionProgressService).fail(eq(execution), any());
		verify(operationLockService, never()).acquireFor(anyLong(), any(), any(Path[].class));
		verify(executionQueue, never()).countAttempt(anyLong(), anyString(), anyInt());
		verify(handler, never()).handle(any(), any(), any());
	}

	/**
	 * The guard that closes the poison-job loop. A refused count means the budget
	 * is spent, and nothing may run - not even once more.
	 */
	@Test
	void runsNothingWhenTheAttemptCannotBeCounted(@TempDir Path folder) {
		reserved(folder);

		when(executionQueue.countAttempt(eq(7L), anyString(), anyInt())).thenReturn(false);

		dispatcher.dispatchOne();

		verify(handler, never()).handle(any(), any(), any());
	}

	@Test
	void reportsAPoisonJobOnlyWhileItIsStillOurs(@TempDir Path folder) {
		reserved(folder);

		when(executionQueue.countAttempt(eq(7L), anyString(), anyInt())).thenReturn(false);

		execution.setClaimedBy("another-worker");

		dispatcher.dispatchOne();

		verify(executionProgressService, never()).fail(any(), any());
	}

	@Test
	void writesTheOutcomeWhenTheHandlerDiesWithoutWritingOne(@TempDir Path folder) {
		reserved(folder);

		when(executionQueue.countAttempt(eq(7L), anyString(), anyInt())).thenReturn(true);

		doThrow(new IllegalStateException("boom")).when(handler).handle(any(), any(), any());

		dispatcher.dispatchOne();

		verify(executionProgressService).fail(eq(execution), any());
	}

	/**
	 * A database that was restarting says nothing about the work: the same
	 * execution, run again in a minute, is the one that succeeds. Ending it as an
	 * error would read in the history as though the files were the problem.
	 */
	@Test
	void putsTheExecutionBackWhenTheFailureWasThePassingKind(@TempDir Path folder) {
		reserved(folder);

		when(executionQueue.countAttempt(eq(7L), anyString(), anyInt())).thenReturn(true);

		doThrow(new DataAccessResourceFailureException("cluster restarting")).when(handler).handle(any(), any(),
				any());

		dispatcher.dispatchOne();

		verify(executionQueue).release(eq(7L), anyString(), anyInt());
		verify(executionProgressService, never()).fail(any(), any());
	}

	/**
	 * Unless there is nothing left to try with. A row returned to a queue that
	 * filters on the attempt budget is a row nobody will ever claim again, so it
	 * ends where somebody can see it instead of waiting out of sight.
	 */
	@Test
	void endsAPoisonJobRatherThanQueueingItInvisibly(@TempDir Path folder) {
		reserved(folder);

		when(executionQueue.countAttempt(eq(7L), anyString(), anyInt())).thenReturn(true);

		execution.setClaimCount(WorkerProperties.DEFAULT_MAX_CLAIMS);

		doThrow(new DataAccessResourceFailureException("cluster restarting")).when(handler).handle(any(), any(),
				any());

		dispatcher.dispatchOne();

		verify(executionProgressService).fail(eq(execution), any());
		verify(executionQueue, never()).release(anyLong(), anyString(), anyInt());
	}

	/**
	 * One request may wait while another of the same runs, but two may not wait -
	 * so a job handed back while its successor is already queued has nothing left
	 * to add. Refused, not cancelled: cancelled is what a person's click means,
	 * and somebody reading the history to ask whether their cancel worked must not
	 * find this instead.
	 */
	@Test
	void refusesAnExecutionItsSuccessorHasAlreadyReplaced(@TempDir Path folder) {
		reserved(folder);

		when(operationLockService.acquireFor(anyLong(), any(), any(Path[].class)))
				.thenThrow(new OperationLockException("busy"));
		when(executionQueue.release(eq(7L), anyString(), anyInt())).thenReturn(false);
		when(executionQueue.hasWaitingDuplicate(7L)).thenReturn(true);

		dispatcher.dispatchOne();

		verify(executionProgressService).reject(eq(execution), any());
		verify(executionProgressService, never()).fail(any(), any());
	}

	/**
	 * A hand-back that changed nothing and has no successor means the row moved on
	 * under us - recovery, a cancel, another worker. Not ours to write an outcome
	 * for.
	 */
	@Test
	void writesNothingWhenTheRowMovedOnInsteadOfBeingSuperseded(@TempDir Path folder) {
		reserved(folder);

		when(operationLockService.acquireFor(anyLong(), any(), any(Path[].class)))
				.thenThrow(new OperationLockException("busy"));
		when(executionQueue.release(eq(7L), anyString(), anyInt())).thenReturn(false);
		when(executionQueue.hasWaitingDuplicate(7L)).thenReturn(false);

		dispatcher.dispatchOne();

		verify(executionProgressService, never()).reject(any(), any());
		verify(executionProgressService, never()).fail(any(), any());
	}

	/**
	 * Losing the locks is not the job failing. The files are as they were, and a
	 * row saying "error" would blame the work for the ground going out from under
	 * it - which also matters to whoever reads the history to decide whether to
	 * run it again.
	 */
	@Test
	void endsAsInterruptedWhenTheHandlerFindsItsLocksGone(@TempDir Path folder) {
		reserved(folder);

		when(executionQueue.countAttempt(eq(7L), anyString(), anyInt())).thenReturn(true);

		doThrow(new OwnershipLostException("gone")).when(handler).handle(any(), any(), any());

		dispatcher.dispatchOne();

		verify(executionProgressService).interrupt(eq(execution), any());
		verify(executionProgressService, never()).fail(any(), any());
	}

	/**
	 * A handler can need paths beyond the pair taken here - an undo reads its
	 * movements and locks wherever each file originally came from. Finding one of
	 * those busy says nothing about the work, so the answer is the same as for the
	 * first pair: hand it back and let whoever holds it finish.
	 */
	@Test
	void handsTheExecutionBackWhenTheHandlerCannotTakeThePathsItAlsoNeeds(@TempDir Path folder) {
		reserved(folder);

		when(executionQueue.countAttempt(eq(7L), anyString(), anyInt())).thenReturn(true);

		doThrow(new OperationLockException("the tree is busy")).when(handler).handle(any(), any(), any());

		dispatcher.dispatchOne();

		verify(executionQueue).release(eq(7L), anyString(), anyInt());
		verify(executionProgressService, never()).fail(any(), any());
		verify(executionProgressService, never()).interrupt(any(), any());
	}

	/**
	 * Between taking the locks and getting here there was a round trip to the
	 * database. A session that died in it would leave this worker holding nothing
	 * while believing otherwise, so nothing of the domain starts until the answer
	 * is asked for again.
	 */
	@Test
	void refusesToStartWorkWhenTheLocksWentAwayBeforeItBegan(@TempDir Path folder) {
		reserved(folder);

		when(executionQueue.countAttempt(eq(7L), anyString(), anyInt())).thenReturn(true);
		doThrow(new OwnershipLostException("gone")).when(ownership).assertStillOwned();

		dispatcher.dispatchOne();

		verify(handler, never()).handle(any(), any(), any());
		verify(executionProgressService).interrupt(eq(execution), any());
	}

	@Test
	void takesNothingItHasNoHandlerFor(@TempDir Path folder) {
		reserved(folder);

		dispatcher.dispatchOne();

		verify(executionQueue).reserve(anyString(), eq(List.of(TYPE)), anyInt(), anyInt());
	}

	/**
	 * A budget that ran out while the row is still ours is the poison job the
	 * counter exists to stop, and the only case where the dispatcher writes the
	 * outcome itself.
	 */
	@Test
	void reportsThePoisonJobWhileTheRowIsStillOurs(@TempDir Path folder) {
		reserved(folder);

		when(executionQueue.countAttempt(eq(7L), anyString(), anyInt())).thenReturn(false);

		execution.setClaimedBy(new WorkerIdentity().workerId());

		dispatcher.dispatchOne();

		verify(handler, never()).handle(any(), any(), any());
	}

	/**
	 * The row can disappear between the claim and the read - a purge, a restore.
	 * Nothing to run, and nothing to report about something that is gone.
	 */
	@Test
	void runsNothingWhenTheRowVanishedAfterBeingClaimed(@TempDir Path folder) {
		reserved(folder);

		when(executionQueue.countAttempt(eq(7L), anyString(), anyInt())).thenReturn(true);
		when(executionRepository.findById(7L)).thenReturn(Optional.empty());

		assertThat(dispatcher.dispatchOne()).isTrue();

		verify(handler, never()).handle(any(), any(), any());
		verify(executionProgressService, never()).fail(any(), any());
	}

	/**
	 * A type nothing here can run is handed straight back rather than failed: a
	 * handler missing is a deployment problem, and marking the execution ERROR
	 * would throw away a request a complete worker would honour. It cannot even be
	 * claimed now - the question put to the queue names only the types this worker
	 * has room for - so what this asserts is what happens if one arrives anyway.
	 */
	@Test
	void runsNothingWhenNoHandlerAnswersForTheType(@TempDir Path folder) {
		when(executionQueue.reserve(anyString(), any(), anyInt(), anyInt())).thenReturn(
				Optional.of(new ClaimedExecution(7L, ExecutionType.CONVERSION.name(), folder.toString(), null, null)));
		when(executionQueue.countAttempt(eq(7L), anyString(), anyInt())).thenReturn(true);

		assertThat(dispatcher.dispatchOne()).isFalse();

		verify(executionQueue).release(eq(7L), anyString(), anyInt());
		verify(handler, never()).handle(any(), any(), any());
		verify(executionProgressService, never()).fail(any(), any());
	}

	private void reserved(Path folder) {
		when(executionQueue.reserve(anyString(), any(), anyInt(), anyInt()))
				.thenReturn(Optional.of(reservedExecution(folder)));
	}

	private ClaimedExecution reservedExecution(Path folder) {
		return new ClaimedExecution(7L, TYPE, folder.toString(), null, null);
	}

	/**
	 * An ownership that says the locks are still held, which is the ordinary case.
	 */
	private ExecutionOwnership owning() {
		ExecutionOwnership held = mock(ExecutionOwnership.class);

		when(held.executionId()).thenReturn(7L);
		when(held.isStillOwned()).thenReturn(true);

		return held;
	}
}