package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.model.ExecutionStep;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.ExecutionStepRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * The recovery transition as one unit: a conditional statement for the row and
 * the history for it, both or neither.
 *
 * <p>
 * Against a real PostgreSQL, because what is being asserted is what the
 * database ends up holding after a commit and after a rollback - neither of
 * which a mock can be wrong about convincingly. Deliberately not
 * {@code @Transactional}: a test transaction wrapping the frontier would make
 * the rollback under test indistinguishable from the test's own cleanup.
 */
@SpringBootTest
@Testcontainers
class RecoveryTransitionAtomicityIntegrationTest {

	private static final String LEGACY = "a sentence written before the recovery";

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

	/**
	 * The clock the application writes with, so what this test compares against is
	 * in the same frame as what production stored. {@code LocalDateTime.now()} reads
	 * the JVM's default zone while the row was written in the configured one, and
	 * on any machine where the two differ - every CI runner - a fresh lease looked
	 * hours expired and an expired one looked fresh.
	 */
	@Autowired
	private Clock clock;

	@Autowired
	private ExecutionProgressService executionProgressService;

	@Autowired
	private ExecutionRepository executionRepository;

	@MockitoSpyBean
	private ExecutionStepRepository executionStepRepository;

	@BeforeEach
	void anEmptyHistory() {
		executionStepRepository.deleteAll();
		executionRepository.deleteAll();
	}

	/**
	 * The pass that did not win the row. Everything the winner would have written
	 * has to be found exactly as it was - and that includes the columns the
	 * transition clears, which are the easiest ones to lose to a write that was
	 * supposed not to happen.
	 */
	@Test
	void aTransitionThatDidNotWinLeavesEveryColumnAndTheHistoryUntouched() {
		Execution running = executionRepository.saveAndFlush(abandoned().leaseUntil(inTheFuture()).build());

		assertThat(executionProgressService.interruptAbandoned(running, ExecutionMessages.executionInterrupted()))
				.as("its lease is still alive, so there is nothing to recover").isFalse();

		Execution after = reload(running);

		assertThat(after.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
		assertThat(after.getFinishedAt()).isNull();
		assertThat(after.getStatusMessage().getText()).isEqualTo(LEGACY);
		assertThat(after.getStatusMessage().getCode()).isNull();
		assertThat(after.getStatusMessage().getArgs()).isNull();
		assertThat(after.getCurrentItemPercent()).as("the column the winner clears was not cleared").isEqualTo(42);
		assertThat(executionStepRepository.findAll()).isEmpty();
	}

	/**
	 * The pass that won it. One statement wrote the whole meaning of the
	 * transition, and the history for it landed in the same commit.
	 */
	@Test
	void theTransitionThatWonWritesTheWholeRowAndExactlyOneStep() {
		Execution orphan = executionRepository.saveAndFlush(abandoned().leaseUntil(inThePast()).build());

		assertThat(executionProgressService.interruptAbandoned(orphan, ExecutionMessages.executionInterrupted()))
				.isTrue();

		Execution ended = reload(orphan);

		assertThat(ended.getStatus()).isEqualTo(ExecutionStatus.INTERRUPTED);
		assertThat(ended.getFinishedAt()).isNotNull();
		assertThat(ended.getFinishedAt()).as("the instant that decided the expiry is the one recorded as the end")
				.isAfter(ended.getLeaseUntil());
		assertThat(ended.getStatusMessage().getCode())
				.isEqualTo(ExecutionMessages.executionInterrupted().code());
		assertThat(ended.getStatusMessage().getText()).as("the legacy sentence is cleared").isNull();
		assertThat(ended.getCurrentItemPercent()).isNull();

		assertThat(executionStepRepository.findAll()).extracting(ExecutionStep::getStepType)
				.containsExactly(ExecutionStepType.INTERRUPTED);
	}

	/**
	 * The one that matters most. The statement wins, the history then fails, and
	 * the row has to come back as though the recovery never happened - which only
	 * holds if the two are one commit.
	 *
	 * <p>
	 * The failure is injected into the step repository rather than arranged with
	 * data, so it lands after the conditional update has already run and inside
	 * the frontier's own transaction. Nothing here waits for anything.
	 */
	@Test
	void aHistoryThatFailsTakesTheWinningUpdateDownWithIt() {
		Execution orphan = executionRepository.saveAndFlush(abandoned().leaseUntil(inThePast()).build());

		doThrow(new IllegalStateException("the history could not be written"))
				.when(executionStepRepository).save(any());

		ExecutionMessage interrupted = ExecutionMessages.executionInterrupted();

		assertThatThrownBy(() -> executionProgressService.interruptAbandoned(orphan, interrupted))
				.isInstanceOf(IllegalStateException.class);

		Execution unchanged = reload(orphan);

		assertThat(unchanged.getStatus()).as("the winning update was rolled back with the history")
				.isEqualTo(ExecutionStatus.RUNNING);
		assertThat(unchanged.getFinishedAt()).isNull();
		assertThat(unchanged.getStatusMessage().getText()).isEqualTo(LEGACY);
		assertThat(unchanged.getStatusMessage().getCode()).isNull();
		assertThat(unchanged.getCurrentItemPercent()).isEqualTo(42);

		assertThat(stepsInTheDatabase()).isEmpty();
	}

	/**
	 * The status and the step each frontier stands for, over the same structure.
	 */
	@Test
	void failAbandonedEndsItAsAnError() {
		Execution orphan = executionRepository.saveAndFlush(abandoned().leaseUntil(inThePast()).build());

		assertThat(executionProgressService.failAbandoned(orphan, ExecutionMessages.executionInterrupted())).isTrue();

		assertThat(reload(orphan).getStatus()).isEqualTo(ExecutionStatus.ERROR);
		assertThat(executionStepRepository.findAll()).extracting(ExecutionStep::getStepType)
				.containsExactly(ExecutionStepType.ERROR);
	}

	@Test
	void rejectSupersededEndsItAsRefused() {
		Execution orphan = executionRepository.saveAndFlush(abandoned().leaseUntil(inThePast()).build());

		assertThat(executionProgressService.rejectSuperseded(orphan, ExecutionMessages.executionSuperseded()))
				.isTrue();

		assertThat(reload(orphan).getStatus()).isEqualTo(ExecutionStatus.REJECTED);
		assertThat(executionStepRepository.findAll()).extracting(ExecutionStep::getStepType)
				.containsExactly(ExecutionStepType.REJECTED);
	}

	/**
	 * Counted through the spy's own delegate rather than through the spy, because
	 * the test that needs this has told the spy to throw on the way in.
	 */
	private List<ExecutionStep> stepsInTheDatabase() {
		return executionRepository.findAll().stream()
				.flatMap(execution -> executionStepRepository
						.findByExecutionIdOrderByCreatedAtAsc(execution.getId()).stream())
				.toList();
	}

	private Execution reload(Execution execution) {
		return executionRepository.findById(execution.getId()).orElseThrow();
	}

	private Execution.ExecutionBuilder abandoned() {
		return Execution.builder().executionType(ExecutionType.RECONCILE).status(ExecutionStatus.RUNNING)
				.sourcePath("/library").claimedBy("worker-that-was-killed").claimCount(1).recursive(true)
				.executeFlag(true).currentItemPercent(42).statusMessage(StatusMessage.raw(LEGACY));
	}

	private LocalDateTime inThePast() {
		return LocalDateTime.now(clock).minusMinutes(30);
	}

	private LocalDateTime inTheFuture() {
		return LocalDateTime.now(clock).plusMinutes(30);
	}
}