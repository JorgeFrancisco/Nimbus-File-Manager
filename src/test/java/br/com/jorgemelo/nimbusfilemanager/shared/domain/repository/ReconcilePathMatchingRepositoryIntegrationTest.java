package br.com.jorgemelo.nimbusfilemanager.shared.domain.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.OrganizationCandidateRepository;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.projection.MediaLocationReconcileProjection;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.projection.OrganizationCandidate;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Proves the Windows backslash / underscore / drive-root semantics of the
 * reconcile prefix match against a real PostgreSQL engine. The stored paths are
 * plain backslash strings (mere data on the Linux CI DB), and the LIKE pattern
 * is built by {@link PathUtils#descendantLikePattern} with an explicit
 * backslash separator so the test does not depend on the host's file separator.
 * Regression guard for the bug where {@code like concat(folder, '\', '%')}
 * silently matched zero rows because backslash is PostgreSQL's default LIKE
 * escape char and '_' is a LIKE wildcard.
 *
 * <p>
 * Seeded under a drive letter no other test uses, because two of these cases
 * ask what lives under a drive <em>root</em> - and the catalog is one table
 * shared by the whole suite, where a row another class committed under the
 * same letter is a perfectly correct answer to a question never asked here.
 */
class ReconcilePathMatchingRepositoryIntegrationTest extends SharedPostgresIntegrationTest {

	private static final Pageable PAGE = PageRequest.of(0, 50);
	private static final Limit LIMIT = Limit.of(50);
	private static final String SEPARATOR = "\\";

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private OrganizationCandidateRepository organizationCandidateRepository;

	@BeforeEach
	void seed() {
		withoutAPlacement();

		persist("R:\\Media\\a.jpg");
		persist("R:\\Media\\IMG_2026_01.jpg");
		persist("R:\\Media\\sub\\x.jpg");

		withoutAPlacement();

		persist("R:\\top.jpg");
		persist("R:\\MediaOther\\a.jpg");
	}

	@Test
	void findForReconcileMatchesEveryDescendantOfAFolderButNotASiblingPrefix() {
		List<String> matched = reconcilePaths("R:\\Media");

		Assertions.assertThat(matched).containsExactlyInAnyOrder("R:\\Media\\a.jpg", "R:\\Media\\IMG_2026_01.jpg",
				"R:\\Media\\sub\\x.jpg");
	}

	@Test
	void findForReconcileMatchesEveryFileUnderADriveRoot() {
		List<String> matched = reconcilePaths("R:\\");

		Assertions.assertThat(matched).containsExactlyInAnyOrder("R:\\Media\\a.jpg", "R:\\Media\\IMG_2026_01.jpg",
				"R:\\Media\\sub\\x.jpg", "R:\\top.jpg", "R:\\MediaOther\\a.jpg");
	}

	/**
	 * The reconcile reads its candidates in rounds keyed by the last id it saw,
	 * because paging by number made the engine skip every earlier row to serve the
	 * next page. Walked here in rounds smaller than the data: every row has to come
	 * back exactly once, and an empty round is what ends the walk.
	 */
	@Test
	void findForReconcileWalksEveryRowInSmallKeyedRoundsWithoutRepeatingOrSkipping() {
		List<String> walked = new ArrayList<>();

		long afterId = 0;

		for (int round = 0; round < 10; round++) {
			List<MediaLocationReconcileProjection> rows = catalogFileLocationRepository.findForReconcile("R:\\",
					PathUtils.descendantLikePattern("R:\\", SEPARATOR), afterId, Limit.of(2));

			if (rows.isEmpty()) {
				break;
			}

			rows.forEach(row -> walked.add(row.getCurrentPath()));

			afterId = rows.getLast().getCatalogFileId();
		}

		Assertions.assertThat(walked).containsExactlyInAnyOrder("R:\\Media\\a.jpg", "R:\\Media\\IMG_2026_01.jpg",
				"R:\\Media\\sub\\x.jpg", "R:\\top.jpg", "R:\\MediaOther\\a.jpg");
	}

	@Test
	void findForReconcileDoesNotOverMatchWhenTheFolderNameContainsAnUnderscore() {
		persist("R:\\a_b\\f.jpg");
		persist("R:\\aXb\\f.jpg");

		List<String> matched = reconcilePaths("R:\\a_b");

		// The '_' in the folder must be escaped: it is a literal underscore, not a
		// single-character wildcard that would also swallow "D:\aXb\f.jpg".
		Assertions.assertThat(matched).containsExactly("R:\\a_b\\f.jpg");
	}

	@Test
	void findIdsForMetadataRebuildMatchesEveryDescendantOfAFolderButNotASiblingPrefix() {
		List<Long> ids = catalogFileRepository.findIdsForMetadataRebuild("R:\\Media",
				PathUtils.descendantLikePattern("R:\\Media", SEPARATOR), null, null, MetadataRebuildRequest.NO_CUTOFF,
				0L, PAGE);

		List<String> matched = ids.stream().map(this::currentPathOf).toList();

		Assertions.assertThat(matched).containsExactlyInAnyOrder("R:\\Media\\a.jpg", "R:\\Media\\IMG_2026_01.jpg",
				"R:\\Media\\sub\\x.jpg");
	}

	@Test
	void findIdsForMetadataRebuildMatchesEveryFileUnderADriveRoot() {
		List<Long> ids = catalogFileRepository.findIdsForMetadataRebuild("R:\\",
				PathUtils.descendantLikePattern("R:\\", SEPARATOR), null, null, MetadataRebuildRequest.NO_CUTOFF, 0L,
				PAGE);

		List<String> matched = ids.stream().map(this::currentPathOf).toList();

		Assertions.assertThat(matched).containsExactlyInAnyOrder("R:\\Media\\a.jpg", "R:\\Media\\IMG_2026_01.jpg",
				"R:\\Media\\sub\\x.jpg", "R:\\top.jpg", "R:\\MediaOther\\a.jpg");
	}

	@Test
	void findCandidatesMatchEveryDescendantOfAFolderButNotASiblingPrefix() {
		List<String> matched = candidatePaths("R:\\Media");

		Assertions.assertThat(matched).containsExactlyInAnyOrder("R:\\Media\\a.jpg", "R:\\Media\\IMG_2026_01.jpg",
				"R:\\Media\\sub\\x.jpg");
	}

	@Test
	void findCandidatesDoNotOverMatchWhenTheFolderNameContainsAnUnderscore() {
		persist("R:\\a_b\\f.jpg");
		persist("R:\\aXb\\f.jpg");

		List<String> matched = candidatePaths("R:\\a_b");

		// The '_' is escaped in the descendant LIKE pattern, so it is a literal
		// underscore and does not also swallow "D:\aXb\f.jpg".
		Assertions.assertThat(matched).containsExactly("R:\\a_b\\f.jpg");
	}

	private List<String> reconcilePaths(String folder) {
		return catalogFileLocationRepository
				.findForReconcile(folder, PathUtils.descendantLikePattern(folder, SEPARATOR), 0L, LIMIT).stream()
				.map(MediaLocationReconcileProjection::getCurrentPath).toList();
	}

	private List<String> candidatePaths(String folder) {
		return organizationCandidateRepository
				.findCandidates(folder, PathUtils.descendantLikePattern(folder, SEPARATOR), PAGE).getContent().stream()
				.map(OrganizationCandidate::currentPath).toList();
	}

	private String currentPathOf(Long catalogFileId) {
		return catalogFileRepository.findById(catalogFileId).map(CatalogFile::getLocation)
				.map(CatalogFileLocation::getCurrentPath).orElseThrow();
	}

	/**
	 * The cutoff a continuing run uses: only files never analysed, or analysed
	 * before the previous run started, are candidates again. Verified against the
	 * engine because it is the query that decides what "the rest" means.
	 */
	@Test
	void findIdsForMetadataRebuildSkipsWhatWasAnalysedSinceTheCutoff() {
		Instant cutoff = Instant.now();

		stampAnalysis("R:\\Media\\a.jpg", cutoff.plus(Duration.ofMinutes(1)));
		stampAnalysis("R:\\Media\\sub\\x.jpg", cutoff.minus(Duration.ofMinutes(1)));

		List<Long> ids = catalogFileRepository.findIdsForMetadataRebuild("R:\\Media",
				PathUtils.descendantLikePattern("R:\\Media", SEPARATOR), null, null, cutoff, 0L, PAGE);

		List<String> matched = ids.stream().map(this::currentPathOf).toList();

		// a.jpg was rebuilt by the previous run and drops out; the one analysed before
		// it and the one never analysed remain.
		Assertions.assertThat(matched).containsExactlyInAnyOrder("R:\\Media\\IMG_2026_01.jpg", "R:\\Media\\sub\\x.jpg");
	}

	private void stampAnalysis(String path, Instant lastAnalysis) {
		CatalogFile file = catalogFileLocationRepository.findPresentByPath(path, PathFlavor.WINDOWS.name())
				.orElseThrow();

		file.setLastAnalysis(lastAnalysis);

		catalogFileRepository.saveAndFlush(file);
	}

	/**
	 * A catalogued file with no placement of its own.
	 *
	 * <p>
	 * Seeded to push the two id sequences apart, which is the state every real
	 * catalog reaches and a freshly built one never does: while they run together
	 * a walk keyed on the placement and resumed with the file's id agrees with
	 * itself by coincidence, and the day they drift it starts skipping rows.
	 */
	private void withoutAPlacement() {
		catalogFileRepository.saveAndFlush(CatalogFile.builder().extension("jpg").sizeBytes(1_024L)
				.modifiedAt(Instant.now()).fileType(FileType.PHOTO).build());
	}

	private void persist(String path) {
		int separatorIndex = path.lastIndexOf('\\');
		String folder = path.substring(0, separatorIndex);

		CatalogFile file = CatalogFile.builder().extension("jpg").sizeBytes(1_024L)
				.modifiedAt(Instant.now()).fileType(FileType.PHOTO).build();

		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(path).currentFolder(folder)
				.pathFlavor(PathFlavor.WINDOWS).build());

		CatalogFiles.catalogued(catalogFileRepository, catalogFileLocationRepository, file);
	}
}