package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;

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
 * <li>{@code markMissingByIds} stamps {@code lifecycle_changed_at} only on a
 * real ACTIVE -&gt; MISSING transition and never resets an already-missing row
 * (so the retention clock is stable across reconciles).</li>
 * </ul>
 */
@SpringBootTest
@Transactional
@Testcontainers
class CatalogFilePurgeIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private CatalogFileRetentionService catalogFileRetentionService;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private MovementRepository movementRepository;

	@Test
	void purgeRemovesOverdueMissingWithItsPlacementAndKeepsActiveAndRecent() {
		Long overdue = persist("overdue", LifecycleStatus.MISSING, LocalDateTime.now().minusDays(200)).getId();
		Long recent = persist("recent", LifecycleStatus.MISSING, LocalDateTime.now().minusDays(1)).getId();
		Long active = persist("active", LifecycleStatus.ACTIVE, null).getId();

		int removed = catalogFileRetentionService.purgeMissingOlderThan(90);

		Assertions.assertThat(removed).isEqualTo(1);
		Assertions.assertThat(catalogFileRepository.findById(overdue)).isEmpty();
		Assertions.assertThat(catalogFileLocationRepository.findById(overdue)).as("placement cascaded away").isEmpty();
		Assertions.assertThat(catalogFileRepository.findById(recent)).isPresent();
		Assertions.assertThat(catalogFileRepository.findById(active)).isPresent();
	}

	@Test
	void purgeDetachesTheMovementAuditInsteadOfDeletingIt() {
		CatalogFile file = persist("audited", LifecycleStatus.MISSING, LocalDateTime.now().minusDays(200));

		Execution execution = executionRepository.save(Execution.builder().executionType(ExecutionType.ORGANIZATION)
				.status(ExecutionStatus.FINISHED_WITH_ERRORS).startedAt(LocalDateTime.now()).sourcePath("D:/src")
				.targetPath("D:/dst").recursive(true).executeFlag(true).filesFound(1).filesAnalyzed(1).cacheHits(0)
				.filesMoved(1).simulatedFiles(0).errors(0).statusMessage(StatusMessage.raw("done")).build());
		Movement movement = movementRepository.saveAndFlush(Movement.builder().execution(execution).catalogFile(file)
				.sourcePath("D:/src/a").targetPath("D:/dst/a").status(MovementStatus.MOVED).build());

		catalogFileRetentionService.purgeMissingOlderThan(90);

		Assertions.assertThat(catalogFileRepository.findById(file.getId())).isEmpty();
		Assertions.assertThat(movementRepository.findById(movement.getId())).get()
				.extracting(Movement::getCatalogFile).as("movement kept, only detached from the purged file").isNull();
	}

	@Test
	void markMissingByIdsStampsOnTransitionAndDoesNotResetAnAlreadyMissingRow() {
		Long id = persist("transition", LifecycleStatus.ACTIVE, null).getId();

		LocalDateTime firstMark = LocalDateTime.of(2020, Month.JANUARY, 1, 12, 0);
		LocalDateTime secondMark = LocalDateTime.of(2024, Month.JUNE, 1, 12, 0);

		int firstUpdated = catalogFileRepository.markMissingByIds(List.of(id), firstMark);

		CatalogFile afterFirst = catalogFileRepository.findById(id).orElseThrow();

		Assertions.assertThat(firstUpdated).isEqualTo(1);
		Assertions.assertThat(afterFirst.getLifecycleStatus()).isEqualTo(LifecycleStatus.MISSING);
		Assertions.assertThat(afterFirst.getLifecycleChangedAt()).isEqualTo(firstMark);

		int secondUpdated = catalogFileRepository.markMissingByIds(List.of(id), secondMark);

		CatalogFile afterSecond = catalogFileRepository.findById(id).orElseThrow();

		Assertions.assertThat(secondUpdated).as("already MISSING, no real transition").isZero();
		Assertions.assertThat(afterSecond.getLifecycleChangedAt()).as("retention clock not reset").isEqualTo(firstMark);
	}

	private CatalogFile persist(String key, LifecycleStatus status, LocalDateTime lifecycleChangedAt) {
		String path = "C:/test/" + key + "-" + System.nanoTime() + ".jpg";

		CatalogFile file = CatalogFile.builder().fileKey("catalog-purge-" + key + "-" + System.nanoTime())
				.fileName(key + ".jpg").extension("jpg").sizeBytes(1L).modifiedAt(LocalDateTime.now())
				.fileType(FileType.PHOTO).lifecycleStatus(status).lifecycleChangedAt(lifecycleChangedAt).build();
		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(path).currentFolder("C:/test")
				.originalPath(path).originalFolder("C:/test").build());

		return catalogFileRepository.saveAndFlush(file);
	}
}