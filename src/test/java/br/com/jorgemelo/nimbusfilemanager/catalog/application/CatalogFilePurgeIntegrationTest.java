package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.CatalogFactProvenance;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.CatalogLifecycleWriter;

/**
 * Destructive-path validation of the catalog missing-record purge against a
 * real Postgres (the SQL cascade/SET-NULL behaviour a Mockito test cannot
 * cover):
 * <ul>
 * <li>an overdue MISSING row and its placement are removed, while ACTIVE and
 * recently-missing rows survive;</li>
 * <li>a movement audit row that referenced the purged file is detached
 * ({@code ON DELETE SET NULL}), not deleted, so history is preserved and the
 * bulk delete never trips the foreign key;</li>
 * <li>the door that records a file as missing stamps {@code
 * lifecycle_changed_at} only on a real ACTIVE -&gt; MISSING transition and never
 * resets an already-missing row (so the retention clock is stable across
 * reconciles).</li>
 * </ul>
 */
class CatalogFilePurgeIntegrationTest extends SharedPostgresIntegrationTest {

	@Autowired
	private CatalogFileRetentionService catalogFileRetentionService;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private MovementRepository movementRepository;

	@Autowired
	private CatalogLifecycleWriter catalogLifecycleWriter;

	@Test
	void purgeRemovesOverdueMissingWithItsPlacementAndKeepsActiveAndRecent() {
		Long overdue = persist("overdue", LifecycleStatus.MISSING, Instant.now().minus(Duration.ofDays(200))).getId();
		Long recent = persist("recent", LifecycleStatus.MISSING, Instant.now().minus(Duration.ofDays(1))).getId();
		Long active = persist("active", LifecycleStatus.ACTIVE, null).getId();

		int removed = catalogFileRetentionService.purgeMissingOlderThan(90, Takings.unfenced(1L)).orElseThrow();

		Assertions.assertThat(removed).isEqualTo(1);
		Assertions.assertThat(catalogFileRepository.findById(overdue)).isEmpty();
		// Asked of the file: a placement has an id of its own, and the two sequences
		// only run together in a database built in one pass.
		Assertions.assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM catalog_file_location WHERE catalog_file_id = ?", Integer.class, overdue))
				.as("placement cascaded away").isZero();
		Assertions.assertThat(catalogFileRepository.findById(recent)).isPresent();
		Assertions.assertThat(catalogFileRepository.findById(active)).isPresent();
	}

	@Test
	void purgeTakesTheMovementsOfTheFileWithIt() {
		CatalogFile file = persist("audited", LifecycleStatus.MISSING, Instant.now().minus(Duration.ofDays(200)));

		Execution execution = executionRepository.save(Execution.builder().executionType(ExecutionType.ORGANIZATION)
				.status(ExecutionStatus.FINISHED_WITH_ERRORS).startedAt(LocalDateTime.now()).sourcePath("D:/src")
				.targetPath("D:/dst").recursive(true).executeFlag(true).filesFound(1).filesAnalyzed(1).cacheHits(0)
				.filesMoved(1).simulatedFiles(0).errors(0).statusMessage(StatusMessage.raw("done")).build());
		Movement movement = movementRepository.saveAndFlush(Movement.builder().execution(execution).catalogFile(file)
				.requestedSourcePath("D:/src/a").requestedTargetPath("D:/dst/a").status(MovementStatus.MOVED)
				.movedAt(Instant.now()).build());

		catalogFileRetentionService.purgeMissingOlderThan(90, Takings.unfenced(1L)).orElseThrow();

		Assertions.assertThat(catalogFileRepository.findById(file.getId())).isEmpty();
		Assertions.assertThat(movementRepository.findById(movement.getId()))
				.as("the operation was this file's, and the file is gone for good").isEmpty();
	}

	@Test
	void theMissingDoorStampsOnTransitionAndDoesNotResetAnAlreadyMissingRow() {
		Long id = persist("transition", LifecycleStatus.ACTIVE, null).getId();

		Instant firstMark = Instant.parse("2020-01-01T12:00:00Z");
		Instant secondMark = Instant.parse("2024-06-01T12:00:00Z");

		int firstUpdated = catalogLifecycleWriter.markMissing(List.of(id), notFoundAt(firstMark));

		// Reloaded rather than read from the session: the door writes by JDBC, and an
		// entity already loaded here would answer what it was loaded as.
		entityManager.clear();

		CatalogFile afterFirst = catalogFileRepository.findById(id).orElseThrow();

		Assertions.assertThat(firstUpdated).isEqualTo(1);
		Assertions.assertThat(afterFirst.getLifecycleStatus()).isEqualTo(LifecycleStatus.MISSING);
		Assertions.assertThat(afterFirst.getLifecycleChangedAt()).isEqualTo(firstMark);

		int secondUpdated = catalogLifecycleWriter.markMissing(List.of(id), notFoundAt(secondMark));

		entityManager.clear();

		CatalogFile afterSecond = catalogFileRepository.findById(id).orElseThrow();

		Assertions.assertThat(secondUpdated).as("already MISSING, no real transition").isZero();
		Assertions.assertThat(afterSecond.getLifecycleChangedAt()).as("retention clock not reset").isEqualTo(firstMark);
	}

	/** A pass that walked the tree and found nothing at the path it expected. */
	private CatalogFactProvenance notFoundAt(Instant observedAt) {
		return new CatalogFactProvenance(observedAt, CatalogEventSources.RECONCILE,
				CatalogEventEvidence.PATH_NOT_FOUND, null);
	}

	private CatalogFile persist(String key, LifecycleStatus status, Instant lifecycleChangedAt) {
		String path = "C:/test/" + key + "-" + System.nanoTime() + ".jpg";

		CatalogFile file = CatalogFile.builder()
				.extension("jpg").sizeBytes(1L).modifiedAt(Instant.now())
				.fileType(FileType.PHOTO).lifecycleStatus(status).lifecycleChangedAt(lifecycleChangedAt).build();
		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(path).currentFolder("C:/test")
				.pathFlavor(PathFlavor.WINDOWS).build());

		return CatalogFiles.catalogued(catalogFileRepository, catalogFileLocationRepository, file);
	}
}