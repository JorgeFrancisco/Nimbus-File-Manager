package br.com.jorgemelo.nimbusfilemanager.shared.domain.repository;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateExclusionService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.DuplicateExclusionFile;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.DuplicateFolderExclusion;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.DuplicateExclusionFileRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.DuplicateFolderExclusionRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.DuplicateRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateFileRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateFileWithShaRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateGroupRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateSummaryProjection;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;

/**
 * Runtime checks that {@link DuplicateRepository}'s always-on exclusion filters
 * behave against a real PostgreSQL instance: a byte-identical group is hidden
 * file-by-file (by the file's own identity) and folder-by-folder (recursive path
 * prefix), and a group that drops below two comparable files disappears from
 * both the group listing and the summary - all without touching the files
 * themselves.
 */
class DuplicateExclusionRepositoryIntegrationTest extends SharedPostgresIntegrationTest {

	private static final Pageable PAGE = PageRequest.of(0, 50);
	private static final String SHA = "a".repeat(64);
	private static final Set<FileType> PHOTOS = Set.of(FileType.PHOTO);

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private DuplicateRepository duplicateRepository;

	@Autowired
	private DuplicateExclusionFileRepository fileExclusionRepository;

	@Autowired
	private DuplicateFolderExclusionRepository folderExclusionRepository;

	@Autowired
	private DuplicateExclusionService duplicateExclusionService;

	private CatalogFile folderA1;
	private CatalogFile folderA2;
	private CatalogFile folderB1;

	@BeforeEach
	void seed() {
		// Three byte-identical photos (same sha256): two in folder A, one in folder B.
		folderA1 = persist("C:/Media/a");
		folderA2 = persist("C:/Media/a");
		folderB1 = persist("C:/Media/b");
	}

	@Test
	void groupAndFilesIncludeEveryCopyWhenNothingIsExcluded() {
		Page<DuplicateGroupRawResponse> groups = duplicateRepository.findDuplicateGroups(PHOTOS, PAGE);

		Assertions.assertThat(groups.getContent()).singleElement()
				.satisfies(group -> Assertions.assertThat(group.files()).isEqualTo(3));

		Assertions.assertThat(duplicateRepository.findDuplicateFiles(SHA)).hasSize(3);
		Assertions.assertThat(duplicateRepository.findDuplicateFilesForShas(List.of(SHA), PHOTOS)).hasSize(3);

		DuplicateSummaryProjection summary = duplicateRepository.summary();

		Assertions.assertThat(summary.getGroups()).isEqualTo(1);
		Assertions.assertThat(summary.getDuplicatedFiles()).isEqualTo(3);
	}

	@Test
	void excludingOneFileDropsOnlyThatCopyAndKeepsTheGroup() {
		fileExclusionRepository.save(DuplicateExclusionFile.builder().catalogFile(folderA1)
				.contentRevision(folderA1.getContentRevision()).build());

		Page<DuplicateGroupRawResponse> groups = duplicateRepository.findDuplicateGroups(PHOTOS, PAGE);

		Assertions.assertThat(groups.getContent()).singleElement()
				.satisfies(group -> Assertions.assertThat(group.files()).isEqualTo(2));

		Assertions.assertThat(duplicateRepository.findDuplicateFiles(SHA)).extracting(DuplicateFileRawResponse::id)
				.containsExactlyInAnyOrder(folderA2.getCatalogFilePublicId(), folderB1.getCatalogFilePublicId());

		Assertions.assertThat(duplicateRepository.findDuplicateFilesForShas(List.of(SHA), PHOTOS))
				.extracting(DuplicateFileWithShaRawResponse::id).doesNotContain(folderA1.getCatalogFilePublicId());
	}

	@Test
	void excludingAFolderDropsEveryCopyUnderItAndCollapsesTheGroup() {
		folderExclusionRepository.save(DuplicateFolderExclusion.builder().folderPath("C:/Media/a").build());

		// Only the single copy in folder B remains, so the group falls below two and
		// vanishes.
		Assertions.assertThat(duplicateRepository.findDuplicateGroups(PHOTOS, PAGE).getContent()).isEmpty();

		Assertions.assertThat(duplicateRepository.findDuplicateFiles(SHA)).extracting(DuplicateFileRawResponse::id)
				.containsExactly(folderB1.getCatalogFilePublicId());

		DuplicateSummaryProjection summary = duplicateRepository.summary();

		Assertions.assertThat(summary.getGroups()).isZero();
	}

	@Test
	void folderExclusionMatchesSubfoldersButNotSiblingPrefixes() {
		CatalogFile nested = persist("C:/Media/a/sub");
		CatalogFile sibling = persist("C:/Media/ab");

		folderExclusionRepository.save(DuplicateFolderExclusion.builder().folderPath("C:/Media/a").build());

		// "C:/Media/a" and "C:/Media/a/sub" are excluded; "C:/Media/ab" only shares a
		// textual prefix and must stay comparable.
		Assertions.assertThat(duplicateRepository.findDuplicateFiles(SHA)).extracting(DuplicateFileRawResponse::id)
				.containsExactlyInAnyOrder(folderB1.getCatalogFilePublicId(), sibling.getCatalogFilePublicId())
				.doesNotContain(nested.getCatalogFilePublicId());
	}

	@Test
	void folderExclusionDoesNotOverMatchWhenTheFolderNameContainsAnUnderscore() {
		CatalogFile underExcluded = persist("C:/Media/a_b/sub");
		CatalogFile sibling = persist("C:/Media/aXb/sub");

		folderExclusionRepository.save(DuplicateFolderExclusion.builder().folderPath("C:/Media/a_b").build());

		// The '_' in the excluded folder is escaped in the LIKE pattern, so it is a
		// literal underscore: only the real descendant of "C:/Media/a_b" is dropped,
		// while the "C:/Media/aXb/sub" sibling (which a wildcard '_' would have
		// swallowed) stays.
		Assertions.assertThat(duplicateRepository.findDuplicateFiles(SHA)).extracting(DuplicateFileRawResponse::id)
				.contains(sibling.getCatalogFilePublicId()).doesNotContain(underExcluded.getCatalogFilePublicId());
	}

	@Test
	void fileExclusionViewJoinsTheCurrentPathForTheManagementList() {
		fileExclusionRepository.save(DuplicateExclusionFile.builder().catalogFile(folderA1)
				.contentRevision(folderA1.getContentRevision()).build());

		Assertions.assertThat(duplicateExclusionService.fileExclusions()).singleElement().satisfies(view -> {
			Assertions.assertThat(view.catalogFilePublicId()).isEqualTo(folderA1.getCatalogFilePublicId());
			Assertions.assertThat(view.currentPath()).startsWith("C:/Media/a/");
			Assertions.assertThat(view.id()).isNotNull();
		});
	}

	@Test
	void folderExclusionMatchesRegardlessOfPathSeparator() {
		// The service stores paths separator-agnostic via PathUtils.normalize, which is
		// OS-native: this back-slash scenario only reproduces on Windows (elsewhere the
		// back-slashes are ordinary filename characters, not separators).
		Assumptions.assumeTrue(File.separatorChar == '\\');

		// A Windows-style current folder (back-slashes) excluded through the service,
		// which stores the path separator-agnostic. The REPLACE-based query must still
		// drop the file and its subfolder copy.
		CatalogFile windowsFolder = persist("C:\\Fotos\\viagem");
		CatalogFile windowsSub = persist("C:\\Fotos\\viagem\\dia1");

		boolean created = duplicateExclusionService.excludeFolder("C:\\Fotos\\viagem");

		Assertions.assertThat(created).isTrue();
		Assertions.assertThat(duplicateRepository.findDuplicateFiles(SHA)).extracting(DuplicateFileRawResponse::id)
				.doesNotContain(windowsFolder.getCatalogFilePublicId(), windowsSub.getCatalogFilePublicId())
				.contains(folderB1.getCatalogFilePublicId());
	}

	private CatalogFile persist(String folder) {
		String key = "dup-excl-it-" + System.nanoTime();
		String path = folder + "/" + key + ".jpg";

		CatalogFile file = CatalogFile.builder().extension("jpg").sizeBytes(1024L)
				.modifiedAt(Instant.now()).fileType(FileType.PHOTO).sha256(SHA).build();

		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(path).currentFolder(folder)
				.pathFlavor(PathFlavor.WINDOWS).build());

		return CatalogFiles.catalogued(catalogFileRepository, catalogFileLocationRepository, file);
	}
}