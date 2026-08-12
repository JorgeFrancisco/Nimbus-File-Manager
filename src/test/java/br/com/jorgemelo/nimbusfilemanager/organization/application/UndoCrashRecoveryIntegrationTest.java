package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.MovementRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PreparedMovement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.MovementWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * An undo whose worker died between putting the file back and saying so.
 *
 * <p>
 * From the disk this is indistinguishable from a file that vanished: the place
 * the undo was going to take it from is empty, and something is sitting where
 * it was going to put it. Read that way it used to be an error, and the file
 * stayed catalogued at a path it had already left. What tells the two apart is
 * the reversal itself, written down before anything moved - and finishing it is
 * recording what happened, not doing it a second time.
 */
@SpringBootTest
@Testcontainers
class UndoCrashRecoveryIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

	@Autowired
	private OrganizationUndoService organizationUndoService;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private MovementRepository movementRepository;

	@Autowired
	private MovementWriter movementWriter;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private AppSettingService appSettingService;

	@Test
	void anUndoWhoseFileAlreadyWentBackIsRecordedNotRepeated(@TempDir Path library) throws IOException {
		Path original = library.resolve("holiday.jpg");
		Path organised = Files.createDirectories(library.resolve("2024")).resolve("holiday.jpg");

		// The state a crash leaves: the file is back where it came from and the
		// catalog still says it is where the organization put it.
		Files.writeString(original, "content");

		// An undo refuses paths outside the library, as it should: this is the
		// library.
		appSettingService.update(SettingsConstants.WATCH_FOLDER, library.toString(), "system");

		CatalogFile file = CatalogFiles.catalogued(new TransactionTemplate(transactionManager),
				catalogFileRepository, catalogFileLocationRepository, organised);

		Execution organisation = execution(ExecutionType.ORGANIZATION, ExecutionStatus.FINISHED, library,
				organised.getParent());

		Movement moved = moved(organisation, file, original, organised);

		Execution undoing = execution(ExecutionType.UNDO, ExecutionStatus.RUNNING, library,
				organised.getParent());

		// What the attempt that died had already reserved, which is what its retry is
		// handed back - identities included.
		PreparedMovement reversal = movementWriter
				.prepare(undoing.getId(),
						List.of(new MovementRequest(file.getId(), organised, original, MovementReason.UNDONE_BY_USER)))
				.getFirst();

		organizationUndoService.undo(organisation.getId(), undoing, Takings.owning(undoing.getId()));

		Assertions.assertThat(Files.exists(original)).as("nothing was moved a second time").isTrue();
		Assertions.assertThat(Files.exists(organised)).isFalse();

		Assertions.assertThat(currentPathOf(file)).as("the catalog followed the file back")
				.isEqualTo(PathUtils.normalize(original));

		// The fact carries the identity reserved before any of it happened, so the
		// history reads as one undo that took two attempts rather than as two undos.
		Assertions.assertThat(factsOf(file)).singleElement()
				.isEqualTo(Map.of("id", reversal.catalogFileEventPublicId(), "type", "MOVED", "old",
						PathUtils.normalize(organised), "new", PathUtils.normalize(original)));

		Assertions.assertThat(statusOf(reversal.movementPublicId())).as("the reversal settled under its own identity")
				.isEqualTo(MovementStatus.MOVED.name());

		Assertions.assertThat(movementRepository.findById(moved.getId()).orElseThrow().getStatus())
				.as("the operation being reversed did move the file, which stays true")
				.isEqualTo(MovementStatus.UNDONE);
	}

	/** With both ends named, as a run that moved files has: the undo locks them. */
	@AfterEach
	void clean() {
		// This drives the real worker path, which commits: nothing here is undone
		// by a rollback, and the next case would meet it.
		jdbcTemplate.update("DELETE FROM movement");
		jdbcTemplate.update("DELETE FROM catalog_file_event");
		jdbcTemplate.update("DELETE FROM catalog_file");
		jdbcTemplate.update("DELETE FROM execution");
	}

	private Execution execution(ExecutionType type, ExecutionStatus status, Path source, Path target) {
		return executionRepository.saveAndFlush(Execution.builder().executionType(type).status(status)
				.startedAt(LocalDateTime.now()).executeFlag(true).recursive(true)
				.sourcePath(PathUtils.normalize(source)).targetPath(PathUtils.normalize(target)).build());
	}

	private Movement moved(Execution execution, CatalogFile file, Path source, Path target) {
		return movementRepository.saveAndFlush(Movement.builder().execution(execution).catalogFile(file)
				.movementPublicId(UUID.randomUUID()).catalogFileEventPublicId(UUID.randomUUID())
				.requestedSourcePath(PathUtils.normalize(source)).requestedTargetPath(PathUtils.normalize(target))
				.status(MovementStatus.MOVED).reason(MovementReason.NONE).movedAt(Instant.now()).build());
	}

	private String currentPathOf(CatalogFile file) {
		return jdbcTemplate.queryForObject("SELECT current_path FROM catalog_file_location WHERE catalog_file_id = ?",
				String.class, file.getId());
	}

	private String statusOf(UUID movementPublicId) {
		return jdbcTemplate.queryForObject("SELECT status FROM movement WHERE movement_public_id = ?", String.class,
				movementPublicId);
	}

	private List<Map<String, Object>> factsOf(CatalogFile file) {
		return jdbcTemplate.queryForList("""
				SELECT catalog_file_event_public_id AS id, event_type AS type, old_path AS old, new_path AS new
				FROM catalog_file_event WHERE catalog_file_id = ? ORDER BY id
				""", file.getId());
	}
}