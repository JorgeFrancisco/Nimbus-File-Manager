package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.MovementRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PreparedMovement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.MovementWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * The history a file carries after being organised and then put back.
 *
 * <p>
 * Two operations and two facts, and they stay four things. The organization
 * moved the file, which remains true however the story continues; the undo is
 * another operation with an identity of its own, and the fact it records says
 * the file went the other way rather than editing the first fact into
 * something it never was. Anyone reading the timeline months later sees both,
 * in the order they happened, each with what it was done by and on what
 * grounds.
 */
@SpringBootTest
@Testcontainers
class OrganizationUndoHistoryIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

	@Autowired
	private OrganizationMovePersistence organizationMovePersistence;

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
	private MovementWriter movementWriter;

	@Autowired
	private AppSettingService appSettingService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void clean() {
		jdbcTemplate.update("DELETE FROM movement");
		jdbcTemplate.update("DELETE FROM catalog_file_event");
		jdbcTemplate.update("DELETE FROM catalog_file");
		jdbcTemplate.update("DELETE FROM execution");
	}

	@Test
	void organisingAFileAndUndoingItLeavesTwoOperationsAndTwoFacts(@TempDir Path library) throws IOException {
		appSettingService.update(SettingsConstants.WATCH_FOLDER, library.toString(), "system");

		Path original = Files.writeString(library.resolve("holiday.jpg"), "content");
		Path organised = Files.createDirectories(library.resolve("2024")).resolve("holiday.jpg");

		CatalogFile file = CatalogFiles.catalogued(new TransactionTemplate(transactionManager),
				catalogFileRepository, catalogFileLocationRepository, original);

		Execution organisation = execution(ExecutionType.ORGANIZATION, ExecutionStatus.FINISHED, library,
				organised.getParent());

		PreparedMovement move = prepare(organisation, file, original, organised, MovementReason.NONE);

		Files.move(original, organised);

		organizationMovePersistence.persistSuccessfulMove(organisation.getId(), move, reloaded(file), original,
				organised, null);

		Assertions.assertThat(currentPathOf(file)).isEqualTo(PathUtils.normalize(organised));

		Execution undoing = execution(ExecutionType.UNDO, ExecutionStatus.RUNNING, library, organised.getParent());

		organizationUndoService.undo(organisation.getId(), undoing, Takings.owning(undoing.getId()));

		Assertions.assertThat(Files.exists(original)).isTrue();
		Assertions.assertThat(currentPathOf(file)).isEqualTo(PathUtils.normalize(original));
		Assertions.assertThat(lifecycleOf(file)).isEqualTo(LifecycleStatus.ACTIVE.name());

		List<Map<String, Object>> facts = factsOf(file);

		// One fact each, in the order they happened, each naming where the file went
		// from and to - the second is not the first rewritten.
		Assertions.assertThat(facts).hasSize(2);
		Assertions.assertThat(facts.getFirst()).containsEntry("id", move.catalogFileEventPublicId())
				.containsEntry("old", PathUtils.normalize(original))
				.containsEntry("new", PathUtils.normalize(organised))
				.containsEntry("source", CatalogEventSources.ORGANIZATION)
				.containsEntry("evidence", CatalogEventEvidence.NIMBUS_OPERATION);
		Assertions.assertThat(facts.getLast()).containsEntry("old", PathUtils.normalize(organised))
				.containsEntry("new", PathUtils.normalize(original))
				.containsEntry("source", CatalogEventSources.ORGANIZATION)
				.containsEntry("evidence", CatalogEventEvidence.NIMBUS_OPERATION);

		Assertions.assertThat(facts.getLast()).as("its own identity, not the one it is reversing")
				.doesNotContainEntry("id", move.catalogFileEventPublicId());

		// A fact is something that happened; a run is something somebody asked for.
		// Naming one after the other would give many facts one identity.
		Assertions.assertThat(facts).extracting(fact -> fact.get("id")).doesNotContainAnyElementsOf(executionIds());

		Assertions.assertThat(facts).allSatisfy(fact -> Assertions.assertThat((Object) fact.get("recordedAt"))
				.as("stamped by the database when the row was written").isNotNull());

		List<Map<String, Object>> operations = operations(file);

		Assertions.assertThat(operations).hasSize(2);
		Assertions.assertThat(operations.getFirst()).containsEntry("id", move.movementPublicId())
				.containsEntry("status", MovementStatus.UNDONE.name());
		Assertions.assertThat(operations.getLast()).containsEntry("status", MovementStatus.MOVED.name())
				.containsEntry("reason", MovementReason.UNDONE_BY_USER.name());

		Assertions.assertThat(operations.getLast()).doesNotContainEntry("id", move.movementPublicId());
	}

	private CatalogFile reloaded(CatalogFile file) {
		return catalogFileRepository.findById(file.getId()).orElseThrow();
	}

	private Execution execution(ExecutionType type, ExecutionStatus status, Path source, Path target) {
		return executionRepository.saveAndFlush(Execution.builder().executionType(type).status(status)
				.startedAt(LocalDateTime.now()).executeFlag(true).recursive(true)
				.sourcePath(PathUtils.normalize(source)).targetPath(PathUtils.normalize(target)).build());
	}

	private PreparedMovement prepare(Execution execution, CatalogFile file, Path from, Path to,
			MovementReason reason) {
		return movementWriter.prepare(execution.getId(), List.of(new MovementRequest(file.getId(), from, to, reason)))
				.getFirst();
	}

	private String currentPathOf(CatalogFile file) {
		return jdbcTemplate.queryForObject("SELECT current_path FROM catalog_file_location WHERE catalog_file_id = ?",
				String.class, file.getId());
	}

	private String lifecycleOf(CatalogFile file) {
		return jdbcTemplate.queryForObject("SELECT lifecycle_status FROM catalog_file WHERE id = ?", String.class,
				file.getId());
	}

	private List<UUID> executionIds() {
		return jdbcTemplate.queryForList("SELECT execution_public_id FROM execution", UUID.class);
	}

	private List<Map<String, Object>> factsOf(CatalogFile file) {
		return jdbcTemplate.queryForList("""
				SELECT catalog_file_event_public_id AS id, old_path AS old, new_path AS new, source,
				       evidence_kind AS evidence, recorded_at AS "recordedAt"
				FROM catalog_file_event WHERE catalog_file_id = ? ORDER BY id
				""", file.getId());
	}

	private List<Map<String, Object>> operations(CatalogFile file) {
		return jdbcTemplate.queryForList("""
				SELECT movement_public_id AS id, status, reason
				FROM movement WHERE catalog_file_id = ? ORDER BY id
				""", file.getId());
	}
}