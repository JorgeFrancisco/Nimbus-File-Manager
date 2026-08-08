package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.model.ExecutionStep;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.ExecutionStepRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * The three ways an execution ends without finishing, asserted against the
 * database rather than against the call.
 *
 * <p>
 * This exists because of a defect a mock could never have shown. {@code reject}
 * and {@code fail} carried no transaction, so the entity they read came back
 * detached: setting the status and the finish time changed an object nobody was
 * going to write. The step went in regardless - {@code save} brings its own
 * transaction - so the history recorded the execution being rejected, over and
 * over, while the row itself stayed RUNNING. Every unit test passed, because
 * every unit test asserted that the service had been called.
 *
 * <p>
 * So nothing here is verified on a mock and nothing is read back from the object
 * that was passed in. Each transition is followed by a fresh read of the row,
 * and the class is deliberately <strong>not</strong> {@code @Transactional}: a
 * test that shared a transaction with the code under test would make a
 * {@code REQUIRES_NEW} boundary meaningless and hide exactly this.
 */
@SpringBootTest
@Testcontainers
class ExecutionProgressServiceIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	@Autowired
	private ExecutionProgressService executionProgressService;

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private ExecutionStepRepository executionStepRepository;

	/** The one that was broken, and the one that stalled a real queue. */
	@Test
	void rejectingMovesTheRowItselfAndNotOnlyTheHistory() {
		Execution running = executionRepository.saveAndFlush(running());

		executionProgressService.reject(running, ExecutionMessages.executionSuperseded());

		Execution reloaded = reload(running);

		assertThat(reloaded.getStatus()).isEqualTo(ExecutionStatus.REJECTED);
		assertThat(reloaded.getFinishedAt()).isNotNull();
		assertThat(reloaded.getCurrentItemPercent()).isNull();

		assertThat(stepsOf(running)).containsExactly(ExecutionStepType.REJECTED);
	}

	@Test
	void failingMovesTheRowItselfAndNotOnlyTheHistory() {
		Execution running = executionRepository.saveAndFlush(running());

		executionProgressService.fail(running, ExecutionMessages.executionInterrupted());

		Execution reloaded = reload(running);

		assertThat(reloaded.getStatus()).isEqualTo(ExecutionStatus.ERROR);
		assertThat(reloaded.getFinishedAt()).isNotNull();

		assertThat(stepsOf(running)).containsExactly(ExecutionStepType.ERROR);
	}

	/**
	 * Included although it was already right. It is the third method on the same
	 * boundary, and the reason the other two lost theirs was that its annotation
	 * sat above its Javadoc, where it reads as belonging to whatever comes next.
	 * Freezing all three is what makes that a build failure rather than a re-read.
	 */
	@Test
	void interruptingMovesTheRowItselfAndNotOnlyTheHistory() {
		Execution running = executionRepository.saveAndFlush(running());

		executionProgressService.interrupt(running, ExecutionMessages.executionInterrupted());

		Execution reloaded = reload(running);

		assertThat(reloaded.getStatus()).isEqualTo(ExecutionStatus.INTERRUPTED);
		assertThat(reloaded.getFinishedAt()).isNotNull();

		assertThat(stepsOf(running)).containsExactly(ExecutionStepType.INTERRUPTED);
	}

	/**
	 * The row and its history are one unit. What made the defect so quiet was that
	 * they were not: the step committed and the row did not, so the two disagreed
	 * and only the step was ever read by anybody.
	 */
	@Test
	void theRowAndItsHistoryAgreeAfterATransition() {
		Execution running = executionRepository.saveAndFlush(running());

		executionProgressService.reject(running, ExecutionMessages.executionSuperseded());

		Execution reloaded = reload(running);

		List<ExecutionStep> steps = executionStepRepository.findByExecutionIdOrderByCreatedAtAsc(running.getId());

		assertThat(steps).hasSize(1);
		assertThat(steps.getFirst().getExecution().getId()).isEqualTo(reloaded.getId());
		assertThat(reloaded.getStatus().isTerminal()).isTrue();
	}

	private Execution reload(Execution execution) {
		return executionRepository.findById(execution.getId()).orElseThrow();
	}

	private List<ExecutionStepType> stepsOf(Execution execution) {
		return executionStepRepository.findByExecutionIdOrderByCreatedAtAsc(execution.getId()).stream()
				.map(ExecutionStep::getStepType).toList();
	}

	private Execution running() {
		return Execution.builder().executionType(ExecutionType.GEO_DATASET_UPDATE).status(ExecutionStatus.RUNNING)
				.claimedBy("worker-that-is-gone").claimCount(1).currentItemPercent(40).recursive(false)
				.executeFlag(true).build();
	}
}