package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.DuplicateFolderExclusion;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

/**
 * The folder-exclusion predicate of the similarity count, against a real
 * PostgreSQL.
 *
 * <p>
 * It exists because a unit test cannot see this defect at all. The query is
 * native, so what a Java text block produces reaches the server verbatim: with
 * the backslash written {@code '\\\\'} the literal arrived as two characters,
 * which {@code standard_conforming_strings = on} refuses as an {@code ESCAPE}
 * (SQLState 22025) and which no Windows path would ever match in the
 * {@code REPLACE}. Nothing short of asking PostgreSQL notices that - the
 * Duplicados screen simply stopped opening.
 *
 * <p>
 * The cases below are the ones the predicate has to get right at once: a folder
 * excludes itself and everything under it, and nothing else. Including the two
 * that turn a wildcard loose if the escaping is wrong - a folder whose name
 * contains {@code _} would match any character in that position, and one
 * containing {@code %} would match any run of them.
 */
@SpringBootTest
@Transactional
@Testcontainers
class SimilarityEligibilityExclusionIntegrationTest {

	private static final String PHOTO_ALGORITHM = "DCT_PHASH_256_V1";

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private MediaFingerprintRepository mediaFingerprintRepository;

	@Autowired
	private DuplicateFolderExclusionRepository duplicateFolderExclusionRepository;

	/**
	 * The query the Duplicados screen runs, for each medium: before the fix it did
	 * not return a wrong number - it raised 22025 and the page never rendered.
	 */
	@ParameterizedTest
	@EnumSource(value = FingerprintKind.class, names = { "PHOTO_PHASH", "VIDEO_PHASH" })
	void theEligibilityCountRunsAtAllForEitherMedium(FingerprintKind kind) {
		fingerprinted(kind, "D:" + backslash() + "livre");

		Assertions.assertThat(mediaFingerprintRepository.countEligibleForSimilarity(kind.name(), algorithmOf(kind)))
				.isEqualTo(1);
	}

	/** A Windows folder that was excluded takes itself and its subtree out. */
	@Test
	void anExcludedWindowsFolderTakesItselfAndWhatIsUnderItOut() {
		exclude("D:/fotos");

		fingerprinted(FingerprintKind.PHOTO_PHASH, "D:" + backslash() + "fotos");
		fingerprinted(FingerprintKind.PHOTO_PHASH, "D:" + backslash() + "fotos" + backslash() + "praia");
		fingerprinted(FingerprintKind.PHOTO_PHASH, "D:" + backslash() + "outra");

		Assertions.assertThat(eligiblePhotos()).as("only the folder outside the exclusion is left").isEqualTo(1);
	}

	/**
	 * The sibling whose name merely starts the same is not under the excluded
	 * folder. Without the {@code '/'} before the wildcard this would silently
	 * exclude it too.
	 */
	@Test
	void aSiblingThatOnlySharesThePrefixIsNotExcluded() {
		exclude("D:/fotos");

		fingerprinted(FingerprintKind.PHOTO_PHASH, "D:" + backslash() + "fotosX");

		Assertions.assertThat(eligiblePhotos()).isEqualTo(1);
	}

	/**
	 * {@code _} matches any single character in a LIKE. Escaped, the excluded
	 * {@code arq_2024} takes out only itself; unescaped it would also take out
	 * {@code arqX2024}.
	 */
	@Test
	void anUnderscoreInTheExcludedFolderIsALetterAndNotAWildcard() {
		exclude("D:/arq_2024");

		fingerprinted(FingerprintKind.PHOTO_PHASH, "D:" + backslash() + "arq_2024" + backslash() + "jan");
		fingerprinted(FingerprintKind.PHOTO_PHASH, "D:" + backslash() + "arqX2024" + backslash() + "jan");

		Assertions.assertThat(eligiblePhotos()).as("only the folder that really is under the exclusion goes")
				.isEqualTo(1);
	}

	/** Same for {@code %}, which would otherwise match any run of characters. */
	@Test
	void aPercentInTheExcludedFolderIsALetterAndNotAWildcard() {
		exclude("D:/100%ok");

		fingerprinted(FingerprintKind.PHOTO_PHASH, "D:" + backslash() + "100%ok" + backslash() + "a");
		fingerprinted(FingerprintKind.PHOTO_PHASH, "D:" + backslash() + "100ZZZok" + backslash() + "a");

		Assertions.assertThat(eligiblePhotos()).isEqualTo(1);
	}

	/** Nothing excluded, nothing lost. */
	@Test
	void withNoExclusionEveryFingerprintedFileIsEligible() {
		fingerprinted(FingerprintKind.PHOTO_PHASH, "D:" + backslash() + "a");
		fingerprinted(FingerprintKind.PHOTO_PHASH, "D:" + backslash() + "b");

		Assertions.assertThat(eligiblePhotos()).isEqualTo(2);
	}

	private long eligiblePhotos() {
		return mediaFingerprintRepository.countEligibleForSimilarity(FingerprintKind.PHOTO_PHASH.name(),
				PHOTO_ALGORITHM);
	}

	private void exclude(String folderPath) {
		duplicateFolderExclusionRepository.saveAndFlush(
				DuplicateFolderExclusion.builder().folderPath(folderPath).createdAt(LocalDateTime.now()).build());
	}

	/**
	 * A catalogued file in {@code folder}, already fingerprinted - which is what
	 * makes it a candidate the count has to decide about.
	 */
	private void fingerprinted(FingerprintKind kind, String folder) {
		String unique = String.valueOf(System.nanoTime());
		String path = folder + backslash() + unique + ".jpg";

		CatalogFile file = CatalogFile.builder().fileKey("eligibility-" + unique).fileName(unique + ".jpg")
				.extension("jpg").sizeBytes(1L).modifiedAt(LocalDateTime.now())
				.fileType(kind == FingerprintKind.PHOTO_PHASH ? FileType.PHOTO : FileType.VIDEO).build();

		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(path).currentFolder(folder)
				.originalPath(path).originalFolder(folder).build());

		CatalogFile saved = catalogFileRepository.saveAndFlush(file);

		mediaFingerprintRepository.saveAll(List.of(MediaFingerprint.builder().catalogFileId(saved.getId()).kind(kind)
				.algorithm(algorithmOf(kind)).sampleIndex(0).hashBytes(new byte[32]).sampleBytes(new byte[1024])
				.computedAt(LocalDateTime.now()).build()));
		mediaFingerprintRepository.flush();
	}

	private String algorithmOf(FingerprintKind kind) {
		return kind == FingerprintKind.PHOTO_PHASH ? PHOTO_ALGORITHM : "FFMPEG_LANCZOS_PHASH_256_FRAMES_V1";
	}

	/**
	 * Written as a character rather than as an escape so that the separator this
	 * test is about cannot be miscounted by whoever reads it next - which is the
	 * mistake that made the test necessary.
	 */
	private String backslash() {
		return String.valueOf((char) 92);
	}
}