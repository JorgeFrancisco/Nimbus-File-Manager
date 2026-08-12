package br.com.jorgemelo.nimbusfilemanager.shared.domain.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

/**
 * The four queries that ask "before this moment", against a real PostgreSQL.
 *
 * <p>
 * Each of them compares a parameter with a column the application stamps as an
 * instant, and each of them used to take a local date-time. That is not a
 * compile error and no mock can see it: the parameter binds, the query runs,
 * and what comes back is decided by a comparison between two different kinds of
 * value. So every case here puts one row clearly on each side of the cutoff and
 * checks which side was acted on.
 *
 * <p>
 * The distance is hours rather than instants either side of the boundary,
 * because inclusive-versus-exclusive is a different question from this one and
 * a row sitting exactly on the cutoff would be answering both at once.
 */
class CatalogCutoffIntegrationTest extends SharedPostgresIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
	private static final Instant CUTOFF = NOW.minus(Duration.ofDays(90));

	private static final Instant WELL_BEFORE = CUTOFF.minus(Duration.ofDays(10));
	private static final Instant WELL_AFTER = CUTOFF.plus(Duration.ofDays(10));

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private MovementRepository movementRepository;

	@Autowired
	private ExecutionRepository executionRepository;

	/**
	 * The retention purge. What it removes is gone for good, so the row on the
	 * near side of the cutoff surviving is the whole point of the boundary.
	 */
	@Test
	void thePurgeRemovesWhatWentMissingBeforeTheCutoffAndKeepsTheRest() {
		Long overdue = missingSince(WELL_BEFORE).getId();
		Long recent = missingSince(WELL_AFTER).getId();

		int removed = catalogFileRepository.deleteMissingBefore(CUTOFF);

		Assertions.assertThat(removed).isEqualTo(1);
		Assertions.assertThat(catalogFileRepository.findById(overdue)).isEmpty();
		Assertions.assertThat(catalogFileRepository.findById(recent)).isPresent();
	}

	/**
	 * A file the catalog still counts as present is not the purge's business
	 * whatever its dates say, so the cutoff is not the only thing being obeyed.
	 */
	@Test
	void thePurgeLeavesAnActiveFileAloneEvenWhenItsStampIsOld() {
		Long active = catalogued("ativo.jpg", LifecycleStatus.ACTIVE, WELL_BEFORE).getId();

		catalogFileRepository.deleteMissingBefore(CUTOFF);

		Assertions.assertThat(catalogFileRepository.findById(active)).isPresent();
	}

	/**
	 * The quarantine retention, which reads the moment the file was moved into
	 * quarantine rather than anything about the catalog entry.
	 */
	@Test
	void theQuarantineSweepSeesOnlyWhatWasMovedBeforeTheCutoff() {
		Movement overdue = quarantined("antigo.jpg", WELL_BEFORE);
		quarantined("recente.jpg", WELL_AFTER);

		List<Movement> found = movementRepository.findByStatusAndReasonInAndMovedAtBeforeOrderByIdAsc(
				MovementStatus.MOVED, List.of(MovementReason.DUPLICATE_QUARANTINED), CUTOFF,
				PageRequest.of(0, 50)).getContent();

		Assertions.assertThat(found).extracting(Movement::getId).containsExactly(overdue.getId());
	}

	/**
	 * The rebuild cutoff, and the property the screen depends on: the number it
	 * reports and the population it works on are the same answer.
	 *
	 * <p>
	 * They are two queries and always were, which is exactly how they could
	 * disagree - one counting a file the other left out. Asked here for the same
	 * folder at the same instant, they have to name the same files.
	 */
	@Test
	void whatTheRebuildCountsIsWhatTheRebuildFinds() {
		Long stale = analysedAt("nao-analisado.jpg", WELL_BEFORE).getId();

		analysedAt("ja-analisado.jpg", WELL_AFTER);

		String folder = "D:" + backslash() + "Media";
		String pattern = PathUtils.descendantLikePattern(folder, backslash());

		long counted = catalogFileRepository.countForMetadataRebuild(folder, pattern, null, null, CUTOFF);

		List<Long> found = catalogFileRepository.findIdsForMetadataRebuild(folder, pattern, null, null, CUTOFF, 0L,
				PageRequest.of(0, 50));

		Assertions.assertThat(found).containsExactly(stale);
		Assertions.assertThat(counted).isEqualTo(found.size());
	}

	/** A file never analysed has nothing to skip by and is always a candidate. */
	@Test
	void aFileNeverAnalysedIsCountedAndFoundWhateverTheCutoffIs() {
		Long never = analysedAt("nunca.jpg", null).getId();

		String folder = "D:" + backslash() + "Media";
		String pattern = PathUtils.descendantLikePattern(folder, backslash());

		Assertions.assertThat(catalogFileRepository.findIdsForMetadataRebuild(folder, pattern, null, null, CUTOFF, 0L,
				PageRequest.of(0, 50))).containsExactly(never);
		Assertions.assertThat(catalogFileRepository.countForMetadataRebuild(folder, pattern, null, null, CUTOFF))
				.isEqualTo(1);
	}

	private CatalogFile missingSince(Instant since) {
		return catalogued("perdido-" + since.toEpochMilli() + ".jpg", LifecycleStatus.MISSING, since);
	}

	private CatalogFile analysedAt(String name, Instant lastAnalysis) {
		CatalogFile file = catalogued(name, LifecycleStatus.ACTIVE, NOW);

		file.setLastAnalysis(lastAnalysis);

		return catalogFileRepository.saveAndFlush(file);
	}

	private CatalogFile catalogued(String name, LifecycleStatus status, Instant lifecycleChangedAt) {
		String folder = "D:" + backslash() + "Media";

		CatalogFile file = CatalogFile.builder().extension("jpg").sizeBytes(1_024L).fileType(FileType.PHOTO)
				.lifecycleStatus(status).lifecycleChangedAt(lifecycleChangedAt).modifiedAt(NOW).importedAt(NOW)
				.build();

		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(folder + backslash() + name)
				.currentFolder(folder).pathFlavor(PathFlavor.WINDOWS).build());

		return CatalogFiles.catalogued(catalogFileRepository, catalogFileLocationRepository, file);
	}

	private Movement quarantined(String name, Instant movedAt) {
		Execution execution = executionRepository.saveAndFlush(Execution.builder()
				.executionType(ExecutionType.DEDUP_DELETE).status(ExecutionStatus.FINISHED).build());

		CatalogFile file = catalogued(name, LifecycleStatus.DELETED, movedAt);

		return movementRepository.saveAndFlush(Movement.builder().movementPublicId(UuidV7.generate())
				.execution(execution).catalogFile(file).requestedSourcePath("D:" + backslash() + "Media" + backslash()
						+ name)
				.requestedTargetPath("D:" + backslash() + "Trash" + backslash() + name).status(MovementStatus.MOVED)
				.reason(MovementReason.DUPLICATE_QUARANTINED).movedAt(movedAt).build());
	}

	/**
	 * Written as a character rather than as an escape so the separator cannot be
	 * miscounted by whoever reads it next.
	 */
	private String backslash() {
		return String.valueOf((char) 92);
	}
}