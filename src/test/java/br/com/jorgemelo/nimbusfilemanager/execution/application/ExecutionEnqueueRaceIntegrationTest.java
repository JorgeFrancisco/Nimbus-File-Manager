package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * Two identical requests arriving at the same instant, in two real
 * transactions.
 *
 * <p>
 * This is the arrangement the application actually starts in: the folder
 * monitor asks for an inventory when the context is ready, and the first poll
 * of the watcher asks for the same one. Both are legitimate, both may land in
 * the same second, and what settles it is the partial unique index - not a
 * look-then-act check, which would let both through precisely when it matters.
 *
 * <p>
 * Not annotated {@code @Transactional}, and that is the point of the class: a
 * test that shares one transaction with the code under test cannot have two
 * transactions collide, so it would assert the property while proving nothing.
 * Each thread here commits on its own connection.
 *
 * <p>
 * What is deliberately <em>not</em> asserted is the absence of an error in the
 * log. The persistence layer reports the refusal at error level on its way out,
 * before anything here can classify it as expected, and that is recorded as
 * known noise on {@code ExecutionEnqueueService} rather than worked around: the
 * behaviour below is what had to be right, and it is.
 */
@SpringBootTest
@Testcontainers
class ExecutionEnqueueRaceIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

	@Autowired
	private ExecutionEnqueueService executionEnqueueService;

	@Autowired
	private ExecutionRepository executionRepository;

	/**
	 * One row, and both callers holding it. The second one is not told "no" and
	 * not given an error - it is given the scan that is already coming, which is
	 * what makes asking twice harmless.
	 */
	@Test
	void twoSimultaneousRequestsForTheSameFolderBecomeOneExecutionBothCallersGet() throws Exception {
		String key = "race-" + UUID.randomUUID();

		ExecutorService threads = Executors.newFixedThreadPool(2);

		CyclicBarrier together = new CyclicBarrier(2);

		try {
			List<Future<Execution>> answers = threads
					.invokeAll(List.of(asking(together, key), asking(together, key)));

			Execution first = answers.get(0).get();
			Execution second = answers.get(1).get();

			assertThat(first.getId()).isNotNull().isEqualTo(second.getId());
			assertThat(first.getStatus()).isEqualTo(ExecutionStatus.PENDING);
			assertThat(queuedWith(key)).hasSize(1);
		} finally {
			threads.shutdownNow();
		}
	}

	/**
	 * The same request once the first one is over is a new request, not a
	 * duplicate: the indexes only refuse what is still waiting or still running.
	 */
	@Test
	void anIdenticalRequestAfterTheFirstOneEndedIsQueuedAgain() {
		String key = "again-" + UUID.randomUUID();

		Execution first = executionEnqueueService.enqueueOrExisting(request(key));

		first.setStatus(ExecutionStatus.FINISHED);

		executionRepository.saveAndFlush(first);

		assertThat(executionEnqueueService.enqueueOrExisting(request(key)).getId()).isNotEqualTo(first.getId());
		assertThat(queuedWith(key)).hasSize(2);
	}

	/**
	 * The case that is not a race at all, and the one that made every boot look
	 * like a database error: the request is already on the queue, committed long
	 * before anybody asked again.
	 *
	 * <p>
	 * What is asserted here is the outcome against a real database - one row, and
	 * the second asker holding it. That no insert is even attempted, which is what
	 * actually removes the error from the log, cannot be seen from outside: it is
	 * asserted in {@code ExecutionEnqueueServiceTest}, where the repository can be
	 * asked whether it was called.
	 */
	@Test
	void aRequestAlreadyWaitingIsAnsweredWithTheRowThatIsAlreadyThere() {
		String key = "waiting-" + UUID.randomUUID();

		Execution waiting = executionEnqueueService.enqueueOrExisting(request(key));

		assertThat(executionEnqueueService.enqueueOrExisting(request(key)).getId()).isEqualTo(waiting.getId());
		assertThat(queuedWith(key)).hasSize(1);
	}

	/**
	 * A third asker gets the same one waiting. Nothing accumulates.
	 */
	@Test
	void aThirdRequestReusesTheOneWaitingRatherThanAddingToIt() {
		String key = "third-" + UUID.randomUUID();

		Long first = executionEnqueueService.enqueueOrExisting(request(key)).getId();

		assertThat(executionEnqueueService.enqueueOrExisting(request(key)).getId()).isEqualTo(first);
		assertThat(executionEnqueueService.enqueueOrExisting(request(key)).getId()).isEqualTo(first);
		assertThat(queuedWith(key)).hasSize(1);
	}

	/**
	 * The 1 + 1 rule, which the check before the insert must not quietly turn into
	 * 1 + 0: one running does not forbid a successor, so a request made while an
	 * identical one runs is queued rather than answered with the running one.
	 */
	@Test
	void oneRunningStillAllowsExactlyOneWaitingSuccessor() {
		String key = "successor-" + UUID.randomUUID();

		Execution running = executionEnqueueService.enqueueOrExisting(request(key));

		running.setStatus(ExecutionStatus.RUNNING);

		executionRepository.saveAndFlush(running);

		Execution successor = executionEnqueueService.enqueueOrExisting(request(key));

		assertThat(successor.getId()).isNotEqualTo(running.getId());
		assertThat(successor.getStatus()).isEqualTo(ExecutionStatus.PENDING);

		// And a third asker joins the successor rather than making a second one, which
		// is what the pending index forbids.
		assertThat(executionEnqueueService.enqueueOrExisting(request(key)).getId()).isEqualTo(successor.getId());
		assertThat(queuedWith(key)).hasSize(2);
	}

	/**
	 * A violation that is not the deduplication index is not answered as though it
	 * were. The public id is unique too, and a second row carrying one that exists
	 * is a fault - not a request that was already queued.
	 */
	@Test
	void anotherUniqueViolationIsNotReportedAsADuplicateRequest() {
		String key = "other-" + UUID.randomUUID();

		Execution first = executionEnqueueService.enqueueOrExisting(request(key));

		Execution collidingOnPublicId = request("colliding-" + UUID.randomUUID());

		collidingOnPublicId.setExecutionPublicId(first.getExecutionPublicId());

		assertThatExceptionOfType(DataIntegrityViolationException.class)
				.isThrownBy(() -> executionEnqueueService.enqueueOrExisting(collidingOnPublicId));
	}

	/**
	 * A timer with nothing of its own already active asks, and is admitted. The
	 * baseline the three refusals below are refusals against.
	 */
	@Test
	void aPeriodicRequestIsQueuedWhenNothingEquivalentIsActive() {
		String key = "periodic-free-" + UUID.randomUUID();

		assertThat(executionEnqueueService.enqueueUnlessAlreadyActive(request(key))).isPresent();
		assertThat(queuedWith(key)).hasSize(1);
	}

	/**
	 * One already waiting is the intention the tick was going to state, so the tick
	 * states nothing.
	 */
	@Test
	void aPeriodicRequestIsRefusedWhileAnEquivalentOneWaits() {
		String key = "periodic-waiting-" + UUID.randomUUID();

		executionEnqueueService.enqueueOrExisting(request(key));

		assertThat(executionEnqueueService.enqueueUnlessAlreadyActive(request(key))).isEmpty();
		assertThat(queuedWith(key)).hasSize(1);
	}

	/**
	 * And one already running, which is the case the ordinary rule allows a
	 * successor for and a timer must not. This is the reconcile incident exactly:
	 * a pass longer than the interval, and a tick that used to leave a successor
	 * behind on every turn.
	 */
	@Test
	void aPeriodicRequestIsRefusedWhileAnEquivalentOneRuns() {
		String key = "periodic-running-" + UUID.randomUUID();

		Execution running = executionEnqueueService.enqueueOrExisting(request(key));

		running.setStatus(ExecutionStatus.RUNNING);

		executionRepository.saveAndFlush(running);

		assertThat(executionEnqueueService.enqueueUnlessAlreadyActive(request(key))).isEmpty();
		assertThat(queuedWith(key)).hasSize(1);
	}

	/** Once it ends there is no standing intention, and the next tick may ask. */
	@Test
	void aPeriodicRequestIsQueuedAgainOnceTheLastOneEnded() {
		String key = "periodic-ended-" + UUID.randomUUID();

		Execution finished = executionEnqueueService.enqueueOrExisting(request(key));

		finished.setStatus(ExecutionStatus.FINISHED);

		executionRepository.saveAndFlush(finished);

		assertThat(executionEnqueueService.enqueueUnlessAlreadyActive(request(key))).isPresent();
		assertThat(queuedWith(key)).hasSize(2);
	}

	/** Two libraries are two intentions; neither silences the other. */
	@Test
	void periodicRequestsForDifferentRootsDoNotSilenceEachOther() {
		String one = "periodic-root-one-" + UUID.randomUUID();
		String other = "periodic-root-other-" + UUID.randomUUID();

		executionEnqueueService.enqueueUnlessAlreadyActive(request(one));

		assertThat(executionEnqueueService.enqueueUnlessAlreadyActive(request(other))).isPresent();
		assertThat(queuedWith(one)).hasSize(1);
		assertThat(queuedWith(other)).hasSize(1);
	}

	/**
	 * The semantics a person still has, stated beside the refusals so that
	 * tightening the timer can never be mistaken for tightening the screen. A
	 * request made through the ordinary door is admitted beside one that runs -
	 * that is the 1 + 1 rule, and it is what keeps a second click from being lost.
	 */
	@Test
	void anOrdinaryRequestIsStillAdmittedBesideOneThatRuns() {
		String key = "manual-beside-running-" + UUID.randomUUID();

		Execution running = executionEnqueueService.enqueueOrExisting(request(key));

		running.setStatus(ExecutionStatus.RUNNING);

		executionRepository.saveAndFlush(running);

		assertThat(executionEnqueueService.enqueue(request(key))).isPresent();
		assertThat(queuedWith(key)).hasSize(2);
	}

	/**
	 * Two ticks that read "nothing active" together. The question asked before the
	 * insert is an optimisation; the index is what makes the answer true, and it
	 * answers the same way here as it does for every other caller.
	 */
	@Test
	void twoSimultaneousPeriodicRequestsLeaveOneIntentionRepresented() throws Exception {
		String key = "periodic-race-" + UUID.randomUUID();

		CyclicBarrier together = new CyclicBarrier(2);

		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			List<Future<Optional<Execution>>> answers = executor
					.invokeAll(List.of(askingPeriodically(together, key), askingPeriodically(together, key)));

			for (Future<Optional<Execution>> answer : answers) {
				answer.get();
			}
		} finally {
			executor.shutdownNow();
		}

		assertThat(queuedWith(key)).hasSize(1);
	}

	private Callable<Optional<Execution>> askingPeriodically(CyclicBarrier together, String key) {
		return () -> {
			together.await();

			return executionEnqueueService.enqueueUnlessAlreadyActive(request(key));
		};
	}

	private Callable<Execution> asking(CyclicBarrier together, String key) {
		return () -> {
			together.await();

			return executionEnqueueService.enqueueOrExisting(request(key));
		};
	}

	private List<Execution> queuedWith(String key) {
		return executionRepository.findAll().stream().filter(execution -> key.equals(execution.getDedupKey())).toList();
	}

	private Execution request(String key) {
		return Execution.builder().executionType(ExecutionType.INVENTORY).sourcePath("D:\\fotos").dedupKey(key)
				.recursive(true).executeFlag(true).build();
	}
}