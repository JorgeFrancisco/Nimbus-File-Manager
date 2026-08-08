package br.com.jorgemelo.nimbusfilemanager.worker.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.model.ExecutionStep;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.ExecutionStepRepository;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueue;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * The incident, written down: an abandoned RUNNING execution with an identical
 * request waiting behind it.
 *
 * <p>
 * A worker died holding a dataset update. Its lease lapsed, and a second request
 * for the same thing was queued - which the design allows, one running and one
 * waiting. Recovery then ran on every start and found the abandoned row every
 * time, and every time the history recorded it as rejected while the row stayed
 * RUNNING. The waiting one could never be claimed, because the queue will not
 * write RUNNING twice for one deduplication key, and the geographic dataset
 * simply stopped updating.
 *
 * <p>
 * Both halves are exercised here against a real database: recovery has to move
 * the row, and the claim has to be able to take the successor afterwards. Not
 * {@code @Transactional}, deliberately - the transition being asserted is one
 * that only exists once something commits.
 */
@SpringBootTest
@Testcontainers
class ExecutionReclaimIntegrationTest {

	private static final String KEY = "update";

	private static final int MAX_CLAIMS = 3;

	private static final int LEASE_SECONDS = 120;

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	@Autowired
	private ExecutionReclaim executionReclaim;

	@Autowired
	private ExecutionQueue executionQueue;

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private ExecutionStepRepository executionStepRepository;

	/**
	 * An empty queue before each test, because none of them may share one.
	 *
	 * <p>
	 * Recovery is asked about every abandoned row there is and the claim takes
	 * whatever it may, so a row left behind by the previous test would change both
	 * answers - and the deduplication key these tests are about is the same one,
	 * which the partial index refuses twice over. The steps go first: they point at
	 * the executions.
	 */
	@BeforeEach
	void anEmptyQueue() {
		executionStepRepository.deleteAll();
		executionRepository.deleteAll();
	}

	/**
	 * The whole incident, end to end: the abandoned row leaves RUNNING, its
	 * successor becomes claimable, and taking it writes RUNNING for that key
	 * exactly once - which is what the partial unique index would otherwise
	 * refuse.
	 */
	@Test
	void anAbandonedRunningExecutionLetsItsSuccessorThroughAfterRecovery() {
		Execution abandoned = executionRepository.saveAndFlush(abandonedRunning());
		Execution successor = executionRepository.saveAndFlush(waiting());

		// While it still looks RUNNING, the successor is not claimable: one running
		// and one waiting is legal, two running is what the index forbids.
		assertThat(claim()).isEmpty();

		executionReclaim.reclaimAbandoned();

		Execution recovered = reload(abandoned);

		assertThat(recovered.getStatus()).isEqualTo(ExecutionStatus.REJECTED);
		assertThat(recovered.getFinishedAt()).isNotNull();
		assertThat(reload(successor).getStatus()).isEqualTo(ExecutionStatus.PENDING);

		assertThat(claim()).get().extracting(ClaimedExecution::id).isEqualTo(successor.getId());
		assertThat(reload(successor).getStatus()).isEqualTo(ExecutionStatus.RUNNING);
	}

	/**
	 * And recovery is idempotent, which is what stops the history from filling up.
	 *
	 * <p>
	 * Both roles run recovery at their own start, deliberately - neither can assume
	 * the other ever will. The second pass has to find nothing, and it does for a
	 * reason worth stating: the queue asks for RUNNING rows whose lease lapsed, and
	 * the first pass moved this one out of RUNNING. That was exactly what failed
	 * before: the row never left, so every pass found it again and wrote another
	 * terminal step. Six of them, for one execution, before anybody looked.
	 */
	@Test
	void recoveryRunningTwiceEndsTheExecutionOnceAndWritesOneTerminalStep() {
		Execution abandoned = executionRepository.saveAndFlush(abandonedRunning());

		executionRepository.saveAndFlush(waiting());

		assertThat(executionReclaim.reclaimAbandoned()).isEqualTo(1);
		assertThat(executionReclaim.reclaimAbandoned()).isZero();

		assertThat(terminalStepsOf(abandoned)).containsExactly(ExecutionStepType.REJECTED);
	}

	/**
	 * With nothing waiting, the same abandoned row goes back on the queue instead -
	 * a dataset update can simply be run again. The difference between the two
	 * outcomes is whether a successor exists, and nothing else.
	 */
	@Test
	void anAbandonedRunningExecutionWithNoSuccessorGoesBackOnTheQueue() {
		Execution abandoned = executionRepository.saveAndFlush(abandonedRunning());

		assertThat(executionReclaim.reclaimAbandoned()).isEqualTo(1);

		Execution recovered = reload(abandoned);

		assertThat(recovered.getStatus()).isEqualTo(ExecutionStatus.PENDING);
		assertThat(recovered.getClaimedBy()).isNull();
		assertThat(recovered.getLeaseUntil()).isNull();
	}

	private Optional<ClaimedExecution> claim() {
		return executionQueue.reserve("worker-under-test", List.of(ExecutionType.GEO_DATASET_UPDATE.name()),
				MAX_CLAIMS, LEASE_SECONDS);
	}

	private Execution reload(Execution execution) {
		return executionRepository.findById(execution.getId()).orElseThrow();
	}

	private List<ExecutionStepType> terminalStepsOf(Execution execution) {
		return executionStepRepository.findByExecutionIdOrderByCreatedAtAsc(execution.getId()).stream()
				.map(ExecutionStep::getStepType).filter(step -> step != ExecutionStepType.PROGRESS_UPDATED).toList();
	}

	private Execution abandonedRunning() {
		return Execution.builder().executionType(ExecutionType.GEO_DATASET_UPDATE).status(ExecutionStatus.RUNNING)
				.dedupKey(KEY).claimedBy("worker-that-is-gone").claimedAt(LocalDateTime.now().minusHours(1))
				.leaseUntil(LocalDateTime.now().minusMinutes(30)).claimCount(1).recursive(false).executeFlag(true)
				.build();
	}

	private Execution waiting() {
		return Execution.builder().executionType(ExecutionType.GEO_DATASET_UPDATE).status(ExecutionStatus.PENDING)
				.dedupKey(KEY).recursive(false).executeFlag(true).build();
	}
}