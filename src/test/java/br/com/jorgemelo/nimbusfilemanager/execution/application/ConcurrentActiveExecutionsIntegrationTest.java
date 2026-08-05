package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * What "an inventory is running" means once more than one execution can be
 * running.
 *
 * <p>
 * The guard used to read the newest active execution and compare its type,
 * which answers the question only while the application runs one execution at a
 * time. The worker runs several. This class writes the arrangement that broke
 * it - an inventory, then something else started later - straight into the
 * database, because the defect was never in the comparison but in what the
 * query returned, and only a real query can tell the two apart.
 */
@SpringBootTest
@Testcontainers
class ConcurrentActiveExecutionsIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private InventoryRunningState inventoryRunningState;

	@Autowired
	private ExecutionQueryService executionQueryService;

	/**
	 * The one that used to fail: the conversion started last, so it is what "the
	 * active execution" resolves to, and the inventory underneath it became
	 * invisible to every settings screen that asks.
	 */
	@Test
	void seesAnInventoryThatAnotherExecutionStartedAfterIt() {
		Execution inventory = save(ExecutionType.INVENTORY, ExecutionStatus.RUNNING,
				LocalDateTime.now().minusMinutes(10));
		Execution conversion = save(ExecutionType.CONVERSION, ExecutionStatus.RUNNING, LocalDateTime.now());

		assertThat(inventoryRunningState.isRunning()).isTrue();

		// Spelled out so the reason is on the record and not only in the history: the
		// newest active execution really is the conversion, and reading only that row
		// is what made the guard answer no.
		assertThat(executionQueryService.active()).get()
				.extracting(ExecutionResponse::executionId).isEqualTo(conversion.getPublicId());

		finish(inventory);
		finish(conversion);
	}

	/**
	 * A pending inventory counts: it has been accepted and will run, and a screen
	 * that changes the catalog in the meantime would be racing whatever the worker
	 * picks up next.
	 */
	@Test
	void seesAnInventoryThatIsStillWaitingToBeClaimed() {
		Execution inventory = save(ExecutionType.INVENTORY, ExecutionStatus.PENDING, LocalDateTime.now());

		assertThat(inventoryRunningState.isRunning()).isTrue();

		finish(inventory);
	}

	@Test
	void doesNotSeeAnInventoryThatAlreadyFinished() {
		save(ExecutionType.INVENTORY, ExecutionStatus.FINISHED, LocalDateTime.now().minusHours(1));
		Execution conversion = save(ExecutionType.CONVERSION, ExecutionStatus.RUNNING, LocalDateTime.now());

		assertThat(inventoryRunningState.isRunning()).isFalse();

		finish(conversion);
	}

	/**
	 * Rows outlive the test that wrote them, and the next class to ask "is an
	 * inventory running" shares this database.
	 */
	private void finish(Execution execution) {
		execution.setStatus(ExecutionStatus.FINISHED);
		execution.setFinishedAt(LocalDateTime.now());

		executionRepository.saveAndFlush(execution);
	}

	private Execution save(ExecutionType type, ExecutionStatus status, LocalDateTime startedAt) {
		return executionRepository.saveAndFlush(Execution.builder().executionType(type)
				.triggerEvent(ExecutionTrigger.MANUAL).status(status).sourcePath("D:\\fotos").startedAt(startedAt)
				.recursive(true).executeFlag(true).build());
	}
}