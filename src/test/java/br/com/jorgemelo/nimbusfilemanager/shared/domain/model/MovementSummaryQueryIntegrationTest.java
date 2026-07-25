package br.com.jorgemelo.nimbusfilemanager.shared.domain.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.assertj.core.api.Assertions;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.MovementSummaryResponse;

/**
 * Validates the post-move integrity report's GROUP BY query against a real
 * Postgres, exercising the tricky part the unit test cannot: a {@code null}
 * reason (a plain MOVED) must group into its own row alongside reasoned rows,
 * and the CAST(enum AS string) projection must round-trip.
 */
@SpringBootTest
@Testcontainers
class MovementSummaryQueryIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	private static final Path WORKSPACE = createWorkspace();

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private MovementRepository movementRepository;

	@Autowired
	private ExecutionQueryService executionQueryService;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) throws IOException {
		Files.createDirectories(WORKSPACE.resolve("database"));
		registry.add("nimbus-file-manager.workspace", WORKSPACE::toString);
	}

	@Test
	void summarizeShouldGroupByStatusAndReasonIncludingNullReason() {
		Execution execution = executionRepository.save(Execution.builder().executionType(ExecutionType.ORGANIZATION)
				.status(ExecutionStatus.FINISHED_WITH_ERRORS).startedAt(LocalDateTime.now()).sourcePath("D:/src")
				.targetPath("D:/dst").recursive(true).executeFlag(true).filesFound(4).filesAnalyzed(4).cacheHits(0)
				.filesMoved(2).simulatedFiles(0).errors(1).statusMessage(StatusMessage.raw("done")).build());

		saveMovement(execution, MovementStatus.MOVED, null);
		saveMovement(execution, MovementStatus.MOVED, null);
		saveMovement(execution, MovementStatus.SKIPPED, MovementReason.ALREADY_MOVED);
		saveMovement(execution, MovementStatus.ERROR, MovementReason.INTEGRITY_CHECK_FAILED);

		var summary = executionQueryService.movementSummary(execution.getPublicId());

		// Ordered by count desc: 2 MOVED (null reason), then the two singletons.
		Assertions.assertThat(summary)
				.extracting(MovementSummaryResponse::status, MovementSummaryResponse::reason,
						MovementSummaryResponse::count)
				.containsExactlyInAnyOrder(Tuple.tuple("MOVED", null, 2L), Tuple.tuple("SKIPPED", "ALREADY_MOVED", 1L),
						Tuple.tuple("ERROR", "INTEGRITY_CHECK_FAILED", 1L));

		Assertions.assertThat(summary.getFirst().count()).isEqualTo(2L);
	}

	private void saveMovement(Execution execution, MovementStatus status, MovementReason reason) {
		movementRepository.save(Movement.builder().execution(execution).sourcePath("D:/src/a").targetPath("D:/dst/a")
				.status(status).reason(reason).build());
	}

	private static Path createWorkspace() {
		try {
			return Files.createTempDirectory("nimbus-file-manager-movement-summary-");
		} catch (IOException e) {
			throw new IllegalStateException("Could not create test workspace", e);
		}
	}
}