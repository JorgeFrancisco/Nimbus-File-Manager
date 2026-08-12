package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.MovementRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PreparedMovement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.MovementWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * The history a file carries after being taken out of the library and put back.
 *
 * <p>
 * Two operations and two facts, and the point is that they stay four things.
 * The quarantine moved the file and remains true; the restore is not its
 * reversal - the file may not even go back where it came from - so nothing
 * about the restore is written over the operation that preceded it. Reading
 * this timeline months later is how anyone answers where a photograph went and
 * on whose say-so.
 */
class QuarantineHistoryIntegrationTest extends SharedPostgresIntegrationTest {

	private static final Path LIBRARY = Path.of("D:", "library", "holiday.jpg");
	private static final Path QUARANTINE = Path.of("D:", "trash", "exec-1", "10__holiday.jpg");

	@Autowired
	private QuarantinePersistence quarantinePersistence;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private MovementWriter movementWriter;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@PersistenceContext
	private EntityManager entityManager;

	private CatalogFile file;

	@BeforeEach
	void catalogued() {
		file = CatalogFiles.catalogued(catalogFileRepository, catalogFileLocationRepository, LIBRARY);
	}

	@Test
	void aFileTakenOutAndPutBackCarriesBothOperationsAndBothFacts() {
		long taking = execution(ExecutionType.DEDUP_DELETE);

		PreparedMovement removal = prepare(taking, LIBRARY, QUARANTINE, MovementReason.DUPLICATE_QUARANTINED);

		quarantinePersistence.persistQuarantine(taking, removal, reloaded(), LIBRARY, QUARANTINE, null);

		Assertions.assertThat(lifecycle()).isEqualTo(LifecycleStatus.DELETED.name());
		Assertions.assertThat(currentPath()).isEqualTo(PathUtils.normalize(QUARANTINE));

		long putting = execution(ExecutionType.QUARANTINE_RESTORE);

		PreparedMovement restore = prepare(putting, QUARANTINE, LIBRARY, MovementReason.RESTORED_FROM_QUARANTINE);

		quarantinePersistence.persistRestore(putting, restore, reloaded(), QUARANTINE, LIBRARY, null);

		Assertions.assertThat(lifecycle()).isEqualTo(LifecycleStatus.ACTIVE.name());
		Assertions.assertThat(currentPath()).isEqualTo(PathUtils.normalize(LIBRARY));

		// Each fact under the identity its own operation reserved before the file
		// moved, and each naming where the file went from and to.
		Assertions.assertThat(facts()).containsExactly(
				Map.of("id", removal.catalogFileEventPublicId(), "old", PathUtils.normalize(LIBRARY), "new",
						PathUtils.normalize(QUARANTINE), "source", CatalogEventSources.QUARANTINE, "evidence",
						CatalogEventEvidence.NIMBUS_OPERATION),
				Map.of("id", restore.catalogFileEventPublicId(), "old", PathUtils.normalize(QUARANTINE), "new",
						PathUtils.normalize(LIBRARY), "source", CatalogEventSources.QUARANTINE, "evidence",
						CatalogEventEvidence.NIMBUS_OPERATION));

		Assertions.assertThat(removal.catalogFileEventPublicId())
				.as("two facts, two identities - neither borrowed from a run").isNotEqualTo(
						restore.catalogFileEventPublicId());
		Assertions.assertThat(removal.movementPublicId()).isNotEqualTo(restore.movementPublicId());

		// The operation that took the file out did take it out. A restore is another
		// operation, not that one being undone.
		Assertions.assertThat(operations()).containsExactly(
				Map.of("id", removal.movementPublicId(), "status", "MOVED", "reason",
						MovementReason.DUPLICATE_QUARANTINED.name()),
				Map.of("id", restore.movementPublicId(), "status", "MOVED", "reason",
						MovementReason.RESTORED_FROM_QUARANTINE.name()));
	}

	private CatalogFile reloaded() {
		return catalogFileRepository.findById(file.getId()).orElseThrow();
	}

	private long execution(ExecutionType type) {
		return executionRepository.saveAndFlush(Execution.builder().executionType(type)
				.status(ExecutionStatus.RUNNING).startedAt(LocalDateTime.now()).executeFlag(true).build()).getId();
	}

	private PreparedMovement prepare(long executionId, Path from, Path to, MovementReason reason) {
		return movementWriter.prepare(executionId, List.of(new MovementRequest(file.getId(), from, to, reason)))
				.getFirst();
	}

	/**
	 * Read from the row. The lifecycle travels on the entity and the placement is
	 * written straight to the table, so only a flush puts the two where the same
	 * query can see them - which is also the order the transaction commits in.
	 */
	private String lifecycle() {
		entityManager.flush();

		return jdbcTemplate.queryForObject("SELECT lifecycle_status FROM catalog_file WHERE id = ?", String.class,
				file.getId());
	}

	private String currentPath() {
		entityManager.flush();

		return jdbcTemplate.queryForObject("SELECT current_path FROM catalog_file_location WHERE catalog_file_id = ?",
				String.class, file.getId());
	}

	private List<Map<String, Object>> facts() {
		return jdbcTemplate.queryForList("""
				SELECT catalog_file_event_public_id AS id, old_path AS old, new_path AS new, source,
				       evidence_kind AS evidence
				FROM catalog_file_event WHERE catalog_file_id = ? ORDER BY id
				""", file.getId());
	}

	private List<Map<String, Object>> operations() {
		return jdbcTemplate.queryForList("""
				SELECT movement_public_id AS id, status, reason
				FROM movement WHERE catalog_file_id = ? ORDER BY id
				""", file.getId());
	}
}