package br.com.jorgemelo.nimbusfilemanager.shared.domain.repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.OrganizationCandidateRepository;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.projection.OrganizationCandidate;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.MediaLocationReconcileProjection;
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
 */
@SpringBootTest
@Transactional
@Testcontainers
class ReconcilePathMatchingRepositoryIntegrationTest {

	private static final Pageable PAGE = PageRequest.of(0, 50);
	private static final Limit LIMIT = Limit.of(50);
	private static final String SEPARATOR = "\\";

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private OrganizationCandidateRepository organizationCandidateRepository;

	@BeforeEach
	void seed() {
		persist("D:\\Media\\a.jpg");
		persist("D:\\Media\\IMG_2026_01.jpg");
		persist("D:\\Media\\sub\\x.jpg");
		persist("D:\\top.jpg");
		persist("D:\\MediaOther\\a.jpg");
	}

	@Test
	void findForReconcileMatchesEveryDescendantOfAFolderButNotASiblingPrefix() {
		List<String> matched = reconcilePaths("D:\\Media");

		Assertions.assertThat(matched).containsExactlyInAnyOrder("D:\\Media\\a.jpg", "D:\\Media\\IMG_2026_01.jpg",
				"D:\\Media\\sub\\x.jpg");
	}

	@Test
	void findForReconcileMatchesEveryFileUnderADriveRoot() {
		List<String> matched = reconcilePaths("D:\\");

		Assertions.assertThat(matched).containsExactlyInAnyOrder("D:\\Media\\a.jpg", "D:\\Media\\IMG_2026_01.jpg",
				"D:\\Media\\sub\\x.jpg", "D:\\top.jpg", "D:\\MediaOther\\a.jpg");
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
			List<MediaLocationReconcileProjection> rows = catalogFileLocationRepository.findForReconcile("D:\\",
					PathUtils.descendantLikePattern("D:\\", SEPARATOR), afterId, Limit.of(2));

			if (rows.isEmpty()) {
				break;
			}

			rows.forEach(row -> walked.add(row.getCurrentPath()));

			afterId = rows.getLast().getCatalogFileId();
		}

		Assertions.assertThat(walked).containsExactlyInAnyOrder("D:\\Media\\a.jpg", "D:\\Media\\IMG_2026_01.jpg",
				"D:\\Media\\sub\\x.jpg", "D:\\top.jpg", "D:\\MediaOther\\a.jpg");
	}

	@Test
	void findForReconcileDoesNotOverMatchWhenTheFolderNameContainsAnUnderscore() {
		persist("D:\\a_b\\f.jpg");
		persist("D:\\aXb\\f.jpg");

		List<String> matched = reconcilePaths("D:\\a_b");

		// The '_' in the folder must be escaped: it is a literal underscore, not a
		// single-character wildcard that would also swallow "D:\aXb\f.jpg".
		Assertions.assertThat(matched).containsExactly("D:\\a_b\\f.jpg");
	}

	@Test
	void findIdsForMetadataRebuildMatchesEveryDescendantOfAFolderButNotASiblingPrefix() {
		List<Long> ids = catalogFileRepository.findIdsForMetadataRebuild("D:\\Media",
				PathUtils.descendantLikePattern("D:\\Media", SEPARATOR), null, null,
				MetadataRebuildRequest.NO_CUTOFF, 0L, PAGE);

		List<String> matched = ids.stream().map(this::currentPathOf).toList();

		Assertions.assertThat(matched).containsExactlyInAnyOrder("D:\\Media\\a.jpg", "D:\\Media\\IMG_2026_01.jpg",
				"D:\\Media\\sub\\x.jpg");
	}

	@Test
	void findIdsForMetadataRebuildMatchesEveryFileUnderADriveRoot() {
		List<Long> ids = catalogFileRepository.findIdsForMetadataRebuild("D:\\",
				PathUtils.descendantLikePattern("D:\\", SEPARATOR), null, null, MetadataRebuildRequest.NO_CUTOFF, 0L,
				PAGE);

		List<String> matched = ids.stream().map(this::currentPathOf).toList();

		Assertions.assertThat(matched).containsExactlyInAnyOrder("D:\\Media\\a.jpg", "D:\\Media\\IMG_2026_01.jpg",
				"D:\\Media\\sub\\x.jpg", "D:\\top.jpg", "D:\\MediaOther\\a.jpg");
	}

	@Test
	void findCandidatesMatchEveryDescendantOfAFolderButNotASiblingPrefix() {
		List<String> matched = candidatePaths("D:\\Media");

		Assertions.assertThat(matched).containsExactlyInAnyOrder("D:\\Media\\a.jpg", "D:\\Media\\IMG_2026_01.jpg",
				"D:\\Media\\sub\\x.jpg");
	}

	@Test
	void findCandidatesDoNotOverMatchWhenTheFolderNameContainsAnUnderscore() {
		persist("D:\\a_b\\f.jpg");
		persist("D:\\aXb\\f.jpg");

		List<String> matched = candidatePaths("D:\\a_b");

		// The '_' is escaped in the descendant LIKE pattern, so it is a literal
		// underscore and does not also swallow "D:\aXb\f.jpg".
		Assertions.assertThat(matched).containsExactly("D:\\a_b\\f.jpg");
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
				.map(CatalogFileLocation::getCurrentPath)
				.orElseThrow();
	}

	/**
	 * The cutoff a continuing run uses: only files never analysed, or analysed
	 * before the previous run started, are candidates again. Verified against the
	 * engine because it is the query that decides what "the rest" means.
	 */
	@Test
	void findIdsForMetadataRebuildSkipsWhatWasAnalysedSinceTheCutoff() {
		LocalDateTime cutoff = LocalDateTime.now();

		stampAnalysis("D:\\Media\\a.jpg", cutoff.plusMinutes(1));
		stampAnalysis("D:\\Media\\sub\\x.jpg", cutoff.minusMinutes(1));

		List<Long> ids = catalogFileRepository.findIdsForMetadataRebuild("D:\\Media",
				PathUtils.descendantLikePattern("D:\\Media", SEPARATOR), null, null, cutoff, 0L, PAGE);

		List<String> matched = ids.stream().map(this::currentPathOf).toList();

		// a.jpg was rebuilt by the previous run and drops out; the one analysed before
		// it and the one never analysed remain.
		Assertions.assertThat(matched).containsExactlyInAnyOrder("D:\\Media\\IMG_2026_01.jpg",
				"D:\\Media\\sub\\x.jpg");
	}

	private void stampAnalysis(String path, LocalDateTime lastAnalysis) {
		CatalogFile file = catalogFileRepository.findByFileKey(path).orElseThrow();

		file.setLastAnalysis(lastAnalysis);

		catalogFileRepository.saveAndFlush(file);
	}

	private void persist(String path) {
		int separatorIndex = path.lastIndexOf('\\');
		String folder = path.substring(0, separatorIndex);
		String fileName = path.substring(separatorIndex + 1);

		CatalogFile file = CatalogFile.builder().fileKey(path).fileName(fileName).extension("jpg").sizeBytes(1_024L)
				.modifiedAt(LocalDateTime.now()).fileType(FileType.PHOTO).build();

		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(path).currentFolder(folder)
				.originalPath(path).originalFolder(folder).build());

		catalogFileRepository.saveAndFlush(file);
	}
}