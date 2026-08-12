package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.DuplicateFolderExclusion;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.CompositionRow;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

/**
 * What a similarity run is about, against a real PostgreSQL: every eligible
 * file, in catalog order, with the user's exclusions already applied.
 *
 * <p>
 * The defect this guards against is older than the cap that used to sit here.
 * The exclusions were once subtracted in memory from rows the query had already
 * chosen, and a real library reported 4.936 files analysed of a stated 8.000
 * because 3.064 of the oldest catalogued files sat in excluded folders. The cap
 * is gone and that arithmetic cannot repeat, but the rule it exposed is
 * permanent: <b>the eligible set is chosen by this query, exclusions and all</b>,
 * and a second filter layered on top would be a second chance to disagree with
 * it. The fixture keeps the shape of that library in miniature - a dense run of
 * excluded files right where the ordering starts looking, and the eligible ones
 * behind them.
 *
 * <p>
 * PostgreSQL and not a unit test because the eligibility predicate is native
 * SQL over Windows paths - see
 * {@link SimilarityEligibilityExclusionIntegrationTest} for what a mis-escaped
 * backslash costs there.
 */
class SimilarityEligibleSelectionIntegrationTest extends SharedPostgresIntegrationTest {

	private static final String PHOTO_ALGORITHM = "DCT_PHASH_256_V1";

	/** Enough excluded files that a selection ignoring them would be obvious. */
	private static final int EXCLUDED = 12;
	private static final int ALLOWED = 15;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private MediaFingerprintRepository mediaFingerprintRepository;

	@Autowired
	private DuplicateFolderExclusionRepository duplicateFolderExclusionRepository;

	/**
	 * The premise of the whole test: the excluded files come first in the ordering,
	 * so a selection that read them before applying the exclusions would return
	 * them.
	 */
	@Test
	void theExcludedFilesAreTheOldestOnesInTheCatalog() {
		givenAnExcludedRunFollowedByEligibleFiles();

		Assertions.assertThat(eligibleCount()).isEqualTo(ALLOWED);
		Assertions.assertThat(selected()).as("and none of the excluded run leaked in").hasSize(ALLOWED);
	}

	/** The number the product promises is the number it delivers. */
	@Test
	void everyEligibleFileIsSelectedAndNothingIsCutOffTheEnd() {
		givenAnExcludedRunFollowedByEligibleFiles();

		Assertions.assertThat(selected()).hasSize(ALLOWED).hasSameSizeAs(selected()).isSorted();
		Assertions.assertThat(selected()).hasSize(eligibleCount());
	}

	@Test
	void nothingTheUserExcludedEntersTheAnalysis() {
		givenAnExcludedRunFollowedByEligibleFiles();

		List<CompositionRow> rows = mediaFingerprintRepository.findPhotoCompositionRows(FingerprintKind.PHOTO_PHASH,
				PHOTO_ALGORITHM, selected().toArray(Long[]::new));

		Assertions.assertThat(rows).isNotEmpty()
				.allSatisfy(row -> Assertions.assertThat(row.currentFolder()).doesNotContain("oculta"));
	}

	/**
	 * Deterministic, and therefore repeatable: the same call twice selects the same
	 * files in the same order. The greedy grouping depends on that order, so a
	 * selection that varied between two runs would make the published groups vary
	 * with it for no reason anybody could see.
	 */
	@Test
	void twoRunsSelectTheSameFilesInTheSameOrder() {
		givenAnExcludedRunFollowedByEligibleFiles();

		Assertions.assertThat(selected()).isEqualTo(selected()).isSorted();
	}

	/**
	 * The two sides of the analysis - the application identifying it before
	 * queueing, and the worker running it - select from the same ids, so the digest
	 * can only ever name the subset that was analysed.
	 */
	@Test
	void bothSidesOfTheAnalysisSeeTheSameFiles() {
		givenAnExcludedRunFollowedByEligibleFiles();

		List<Long> ids = selected();

		Assertions.assertThat(mediaFingerprintRepository.findPhotoCompositionRows(FingerprintKind.PHOTO_PHASH,
				PHOTO_ALGORITHM, ids.toArray(Long[]::new))).hasSize(ids.size());
		Assertions.assertThat(mediaFingerprintRepository.findFingerprintedPhotos(FingerprintKind.PHOTO_PHASH,
				PHOTO_ALGORITHM, ids.toArray(Long[]::new))).hasSize(ids.size());
	}

	@Test
	void aSmallLibraryIsAnalysedEntirely() {
		fingerprintedIn("D:" + backslash() + "livre", 4);

		Assertions.assertThat(selected()).hasSize(4);
		Assertions.assertThat(eligibleCount()).isEqualTo(4);
	}

	/**
	 * A library larger than the cap that used to sit here comes back whole.
	 *
	 * <p>
	 * The number is arbitrary now and that is the point: nothing in the query
	 * knows it. Kept as a case because the regression it would catch is a
	 * truncation reappearing - a {@code LIMIT} added back for a batch, a
	 * {@code Pageable} slipped into the signature - and the failure mode of that is
	 * a complete-looking answer about part of the library.
	 */
	@Test
	void aLibraryLargerThanTheOldCapComesBackWhole() {
		fingerprintedIn("D:" + backslash() + "aberta", 13);

		Assertions.assertThat(selected()).hasSize(13);
	}

	private void givenAnExcludedRunFollowedByEligibleFiles() {
		exclude("D:/oculta");

		fingerprintedIn("D:" + backslash() + "oculta", EXCLUDED);
		fingerprintedIn("D:" + backslash() + "aberta", ALLOWED);
	}

	private List<Long> selected() {
		return mediaFingerprintRepository.findEligibleForSimilarity(FingerprintKind.PHOTO_PHASH.name(),
				PHOTO_ALGORITHM);
	}

	private int eligibleCount() {
		return mediaFingerprintRepository.countEligibleForSimilarity(FingerprintKind.PHOTO_PHASH.name(),
				PHOTO_ALGORITHM);
	}

	private void exclude(String folderPath) {
		duplicateFolderExclusionRepository.saveAndFlush(
				DuplicateFolderExclusion.builder().folderPath(folderPath).createdAt(LocalDateTime.now()).build());
	}

	/**
	 * Saved one at a time and flushed, because the ordering under test is the
	 * generated id and the test needs the folders to land in a known sequence.
	 */
	private void fingerprintedIn(String folder, int howMany) {
		for (int index = 0; index < howMany; index++) {
			String unique = folder.hashCode() + "-" + index;
			String path = folder + backslash() + unique + ".jpg";

			CatalogFile file = CatalogFile.builder().fileKey("eligible-" + unique).fileName(unique + ".jpg")
					.extension("jpg").sizeBytes(1L).modifiedAt(LocalDateTime.now()).fileType(FileType.PHOTO).build();

			file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(path).currentFolder(folder)
					.originalPath(path).originalFolder(folder).build());

			CatalogFile saved = catalogFileRepository.saveAndFlush(file);

			mediaFingerprintRepository.saveAndFlush(MediaFingerprint.builder().catalogFileId(saved.getId())
					.kind(FingerprintKind.PHOTO_PHASH).algorithm(PHOTO_ALGORITHM).sampleIndex(0).hashBytes(new byte[32])
					.sampleBytes(new byte[1024]).computedAt(LocalDateTime.now()).build());
		}
	}

	/**
	 * Written as a character rather than as an escape so the separator cannot be
	 * miscounted by whoever reads it next.
	 */
	private String backslash() {
		return String.valueOf((char) 92);
	}
}