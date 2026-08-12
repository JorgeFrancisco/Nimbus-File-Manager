package br.com.jorgemelo.nimbusfilemanager.execution.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * What the history screen shows, and what it keeps to itself.
 *
 * <p>
 * The queue is a complete technical record - every run is a row, including the
 * hundreds of automatic reconciles a quiet library produces in a day. The
 * functional history is the subset a person would want to read, and the only
 * thing it leaves out is an automatic reconcile that finished having changed
 * nothing. Every assertion here is one line of that rule, because a filter that
 * hid one row too many would hide the run someone needed to explain.
 */
@SpringBootTest
@Testcontainers
class FunctionalHistoryIntegrationTest {

	private static final int PAGE_SIZE = 500;

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

	@Autowired
	private ExecutionRepository executionRepository;

	@Test
	void hidesAnAutomaticReconcileThatRepairedNothing() {
		Execution noop = save(ExecutionType.RECONCILE, ExecutionTrigger.TIMER, ExecutionStatus.FINISHED, 0);

		assertThat(isShown(noop)).isFalse();

		// The row is still there: the queue and the audit are complete, and only the
		// screen narrows them.
		assertThat(executionRepository.findById(noop.getId())).isPresent();
	}

	@Test
	void showsAnAutomaticReconcileThatRepairedSomething() {
		assertThat(isShown(save(ExecutionType.RECONCILE, ExecutionTrigger.TIMER,
				ExecutionStatus.FINISHED, 1))).isTrue();
	}

	/**
	 * Someone asked for it, so someone is waiting to see what it found - even if
	 * the answer is nothing.
	 */
	@Test
	void showsAManualReconcileThatRepairedNothing() {
		assertThat(isShown(save(ExecutionType.RECONCILE, ExecutionTrigger.MANUAL,
				ExecutionStatus.FINISHED, 0))).isTrue();
	}

	/**
	 * A reconcile queued after a crash is evidence about that crash, whatever it
	 * ended up repairing.
	 */
	@Test
	void showsAReconcileQueuedByRecovery() {
		assertThat(isShown(save(ExecutionType.RECONCILE, ExecutionTrigger.FILE_EVENT,
				ExecutionStatus.FINISHED, 0))).isTrue();
	}

	@Test
	void neverHidesAFailedReconcile() {
		assertThat(isShown(save(ExecutionType.RECONCILE, ExecutionTrigger.TIMER, ExecutionStatus.ERROR, 0))).isTrue();
	}

	@Test
	void neverHidesAnInterruptedReconcile() {
		assertThat(isShown(save(ExecutionType.RECONCILE, ExecutionTrigger.TIMER, ExecutionStatus.INTERRUPTED,
				0))).isTrue();
	}

	@Test
	void neverHidesACancelledReconcile() {
		assertThat(isShown(save(ExecutionType.RECONCILE, ExecutionTrigger.TIMER, ExecutionStatus.CANCELLED,
				0))).isTrue();
	}

	@Test
	void neverHidesAnotherTypeThatRepairedNothing() {
		assertThat(isShown(save(ExecutionType.INVENTORY, ExecutionTrigger.TIMER, ExecutionStatus.FINISHED,
				0))).isTrue();
	}

	/**
	 * Whether the screen would list this execution. Asked by id rather than by
	 * comparing whole pages, because other integration classes share this database
	 * and a test that assumed it owned the table would be asserting about their
	 * rows too.
	 */
	private boolean isShown(Execution execution) {
		return executionRepository.findFunctionalHistory(PageRequest.of(0, PAGE_SIZE)).getContent().stream()
				.anyMatch(listed -> listed.getId().equals(execution.getId()));
	}

	private Execution save(ExecutionType type, ExecutionTrigger trigger, ExecutionStatus status, int repairedItems) {
		return executionRepository.saveAndFlush(Execution.builder().executionType(type).triggerEvent(trigger)
				.status(status).sourcePath("D:\\fotos").startedAt(LocalDateTime.now()).recursive(true)
				.executeFlag(true).repairedItems(repairedItems).build());
	}
}