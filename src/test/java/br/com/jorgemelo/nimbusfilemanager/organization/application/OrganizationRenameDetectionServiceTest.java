package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneOffset;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.date.DateSourceService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.FileHashes;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.FileSystemDates;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileIssueResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * A rename reaches the watcher as an unrelated delete+create pair, so this
 * service has to re-pair them by content. The risky part is the pairing rule -
 * a wrong merge silently points a catalog record at someone else's file - so
 * every ambiguity case is pinned here alongside the happy path.
 */
class OrganizationRenameDetectionServiceTest {

	private static final LocalDateTime CREATED = LocalDateTime.of(2026, Month.JULY, 20, 8, 0);

	private final CatalogFileRepository catalogFileRepository = mock(CatalogFileRepository.class);
	private final FileHashService fileHashService = mock(FileHashService.class);
	private final DateSourceService dateSourceService = mock(DateSourceService.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-07-25T12:00:00Z"), ZoneOffset.UTC);
	private final OrganizationRenameDetectionService service = new OrganizationRenameDetectionService(
			catalogFileRepository, fileHashService, dateSourceService, clock);

	@TempDir
	Path dir;

	@Test
	void shouldRelocateTheRecordWhenExactlyOneMissingRecordMatchesExactlyOneNewFile() throws IOException {
		Path renamed = write("renamed.jpg", "photo-bytes");

		CatalogFile catalogFile = catalogFile(1L, "C:/media/original.jpg", Files.size(renamed), "sha-a");

		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L))).thenReturn(List.of(catalogFile));
		when(fileHashService.hashes(renamed)).thenReturn(new FileHashes("sha-a", "md5-a"));
		when(dateSourceService.resolveFileSystemDates(renamed)).thenReturn(new FileSystemDates(CREATED, CREATED));

		Assertions.assertThat(service.detectAndApplyRenames(response(catalogFile, renamed))).containsExactly(1L);

		Assertions.assertThat(catalogFile.getFileKey()).isEqualTo(PathUtils.normalize(renamed));
		Assertions.assertThat(catalogFile.getFileName()).isEqualTo("renamed.jpg");
		Assertions.assertThat(catalogFile.getLocation().getCurrentPath()).isEqualTo(PathUtils.normalize(renamed));
		Assertions.assertThat(catalogFile.getLocation().getOriginalPath()).isEqualTo(PathUtils.normalize(renamed));

		verify(catalogFileRepository).save(catalogFile);
	}

	/**
	 * Correcting a wrong extension is a rename, and the extension is what decides
	 * the type. Leaving it behind kept a .mps typed PHOTO: counted on every media
	 * screen and queued for a fingerprint it can never have.
	 */
	@Test
	void shouldRetypeTheRecordWhenTheRenameChangedItsExtension() throws IOException {
		Path renamed = write("measurements.mps", "not-an-image");

		CatalogFile catalogFile = catalogFile(1L, "C:/media/measurements.bmp", Files.size(renamed), "sha-a");

		catalogFile.setFileType(FileType.PHOTO);

		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L))).thenReturn(List.of(catalogFile));
		when(fileHashService.hashes(renamed)).thenReturn(new FileHashes("sha-a", "md5-a"));
		when(dateSourceService.resolveFileSystemDates(renamed)).thenReturn(new FileSystemDates(CREATED, CREATED));

		service.detectAndApplyRenames(response(catalogFile, renamed));

		Assertions.assertThat(catalogFile.getExtension()).isEqualTo("mps");
		Assertions.assertThat(catalogFile.getFileType()).isEqualTo(FileType.OTHER);
	}

	/** A rename that keeps the extension leaves the type exactly where it was. */
	@Test
	void shouldKeepTheTypeWhenTheExtensionSurvivedTheRename() throws IOException {
		Path renamed = write("renamed.jpg", "photo-bytes");

		CatalogFile catalogFile = catalogFile(1L, "C:/media/original.jpg", Files.size(renamed), "sha-a");

		catalogFile.setFileType(FileType.PHOTO);

		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L))).thenReturn(List.of(catalogFile));
		when(fileHashService.hashes(renamed)).thenReturn(new FileHashes("sha-a", "md5-a"));
		when(dateSourceService.resolveFileSystemDates(renamed)).thenReturn(new FileSystemDates(CREATED, CREATED));

		service.detectAndApplyRenames(response(catalogFile, renamed));

		Assertions.assertThat(catalogFile.getFileType()).isEqualTo(FileType.PHOTO);
	}

	@Test
	void shouldDoNothingWhenThereIsNothingMissingOnEitherSide() {
		OrganizationReconcileResponse noMissingOnDisk = response(List.of(), List.of(issue(null, "C:/media/new.jpg")));

		Assertions.assertThat(service.detectAndApplyRenames(noMissingOnDisk)).isEmpty();

		OrganizationReconcileResponse noNewFiles = response(List.of(issue(1L, "C:/media/original.jpg")), List.of());

		Assertions.assertThat(service.detectAndApplyRenames(noNewFiles)).isEmpty();

		verify(catalogFileRepository, never()).findForMetadataRebuildByIds(any());
	}

	@Test
	void shouldStopWhenNoMissingRecordCarriesBothSizeAndHash() throws IOException {
		Path renamed = write("renamed.jpg", "photo-bytes");

		CatalogFile withoutHash = catalogFile(1L, "C:/media/original.jpg", 11L, null);

		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L))).thenReturn(List.of(withoutHash));

		Assertions.assertThat(service.detectAndApplyRenames(response(withoutHash, renamed))).isEmpty();

		verify(fileHashService, never()).hashes(any());
	}

	/**
	 * Hashing is the expensive step, so a disk candidate whose size matches nothing
	 * missing must never be read at all.
	 */
	@Test
	void shouldNotHashADiskCandidateWhoseSizeMatchesNoMissingRecord() throws IOException {
		Path renamed = write("renamed.jpg", "a much longer content than the record");

		CatalogFile catalogFile = catalogFile(1L, "C:/media/original.jpg", 4L, "sha-a");

		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L))).thenReturn(List.of(catalogFile));

		Assertions.assertThat(service.detectAndApplyRenames(response(catalogFile, renamed))).isEmpty();

		verify(fileHashService, never()).hashes(any());
		verify(catalogFileRepository, never()).save(any());
	}

	@Test
	void shouldSkipACandidateWhoseHashingFails() throws IOException {
		Path renamed = write("renamed.jpg", "photo-bytes");

		CatalogFile catalogFile = catalogFile(1L, "C:/media/original.jpg", Files.size(renamed), "sha-a");

		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L))).thenReturn(List.of(catalogFile));
		when(fileHashService.hashes(renamed)).thenThrow(new IllegalStateException("unreadable"));

		Assertions.assertThat(service.detectAndApplyRenames(response(catalogFile, renamed))).isEmpty();

		verify(catalogFileRepository, never()).save(any());
	}

	/**
	 * Two records and two files with identical content and no usable creation time
	 * cannot be told apart, and a wrong merge is worse than a missed one - so
	 * nothing is relocated.
	 */
	@Test
	void shouldLeaveAnAmbiguousGroupAloneWhenCreationTimeCannotBreakTheTie() throws IOException {
		Path firstNew = write("new-a.jpg", "same");
		Path secondNew = write("new-b.jpg", "same");

		CatalogFile first = catalogFile(1L, "C:/media/old-a.jpg", Files.size(firstNew), "sha-same");
		CatalogFile second = catalogFile(2L, "C:/media/old-b.jpg", Files.size(secondNew), "sha-same");

		first.setCreatedAt(null);
		second.setCreatedAt(null);

		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L, 2L))).thenReturn(List.of(first, second));
		when(fileHashService.hashes(any())).thenReturn(new FileHashes("sha-same", "md5"));
		when(dateSourceService.resolveFileSystemDates(any())).thenReturn(new FileSystemDates(CREATED, CREATED));

		OrganizationReconcileResponse response = response(
				List.of(issue(1L, PathUtils.normalize("C:/media/old-a.jpg")),
						issue(2L, PathUtils.normalize("C:/media/old-b.jpg"))),
				List.of(issue(null, firstNew.toString()), issue(null, secondNew.toString())));

		Assertions.assertThat(service.detectAndApplyRenames(response)).isEmpty();

		verify(catalogFileRepository, never()).save(any());
	}

	/**
	 * A real rename keeps the filesystem creation time untouched, so it is the
	 * tie-break that makes an otherwise ambiguous group safe to pair.
	 */
	@Test
	void shouldPairAnAmbiguousGroupByExactCreationTime() throws IOException {
		Path firstNew = write("new-a.jpg", "same");
		Path secondNew = write("new-b.jpg", "same");

		CatalogFile first = catalogFile(1L, "C:/media/old-a.jpg", Files.size(firstNew), "sha-same");
		CatalogFile second = catalogFile(2L, "C:/media/old-b.jpg", Files.size(secondNew), "sha-same");

		LocalDateTime other = CREATED.plusDays(1);

		first.setCreatedAt(CREATED);
		second.setCreatedAt(other);

		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L, 2L))).thenReturn(List.of(first, second));
		when(fileHashService.hashes(any())).thenReturn(new FileHashes("sha-same", "md5"));
		when(dateSourceService.resolveFileSystemDates(firstNew)).thenReturn(new FileSystemDates(CREATED, CREATED));
		when(dateSourceService.resolveFileSystemDates(secondNew)).thenReturn(new FileSystemDates(other, other));

		OrganizationReconcileResponse response = response(
				List.of(issue(1L, PathUtils.normalize("C:/media/old-a.jpg")),
						issue(2L, PathUtils.normalize("C:/media/old-b.jpg"))),
				List.of(issue(null, firstNew.toString()), issue(null, secondNew.toString())));

		Assertions.assertThat(service.detectAndApplyRenames(response)).containsExactlyInAnyOrder(1L, 2L);

		Assertions.assertThat(first.getFileKey()).isEqualTo(PathUtils.normalize(firstNew));
		Assertions.assertThat(second.getFileKey()).isEqualTo(PathUtils.normalize(secondNew));
	}

	/**
	 * The record only moves when the placement that actually went missing is the
	 * one being repointed - a stale sample must never repoint a live location.
	 */
	@Test
	void shouldRefuseToRelocateWhenTheMissingPathIsNotTheRecordsCurrentPath() throws IOException {
		Path renamed = write("renamed.jpg", "photo-bytes");

		CatalogFile catalogFile = catalogFile(1L, "C:/media/original.jpg", Files.size(renamed), "sha-a");

		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L))).thenReturn(List.of(catalogFile));
		when(fileHashService.hashes(renamed)).thenReturn(new FileHashes("sha-a", "md5-a"));
		when(dateSourceService.resolveFileSystemDates(renamed)).thenReturn(new FileSystemDates(CREATED, CREATED));

		OrganizationReconcileResponse response = response(
				List.of(issue(1L, PathUtils.normalize("C:/media/somewhere-else.jpg"))),
				List.of(issue(null, renamed.toString())));

		Assertions.assertThat(service.detectAndApplyRenames(response)).isEmpty();

		verify(catalogFileRepository, never()).save(any());
	}

	/**
	 * The reconcile that listed the issues and this pass are two reads of a moving
	 * catalog. A record deleted in between is dropped from the pairing instead of
	 * leaving an entry that points at no record at all.
	 */
	@Test
	void shouldIgnoreAMissingIssueWhoseRecordIsNoLongerInTheCatalog() throws IOException {
		Path renamed = write("renamed.jpg", "photo-bytes");

		CatalogFile present = catalogFile(1L, "C:/media/original.jpg", Files.size(renamed), "sha-a");

		// Only the first record comes back: the second was deleted between the
		// reconcile that reported it and this pass.
		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L, 2L))).thenReturn(List.of(present));
		when(fileHashService.hashes(renamed)).thenReturn(new FileHashes("sha-a", "md5-a"));
		when(dateSourceService.resolveFileSystemDates(renamed)).thenReturn(new FileSystemDates(CREATED, CREATED));

		OrganizationReconcileResponse response = response(
				List.of(issue(1L, PathUtils.normalize("C:/media/original.jpg")),
						issue(2L, PathUtils.normalize("C:/media/deleted.jpg"))),
				List.of(issue(null, renamed.toString())));

		Assertions.assertThat(service.detectAndApplyRenames(response)).containsExactly(1L);
	}

	/**
	 * Same size, different content: the record stays missing. Pairing by size
	 * alone would repoint it at a file it has nothing to do with.
	 */
	@Test
	void shouldLeaveAMissingRecordWhoseContentAppearedNowhereOnDisk() throws IOException {
		Path renamed = write("renamed.jpg", "photo-bytes");

		CatalogFile matched = catalogFile(1L, "C:/media/original.jpg", Files.size(renamed), "sha-a");
		CatalogFile unmatched = catalogFile(2L, "C:/media/other.jpg", Files.size(renamed), "sha-b");

		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L, 2L))).thenReturn(List.of(matched, unmatched));
		when(fileHashService.hashes(renamed)).thenReturn(new FileHashes("sha-a", "md5-a"));
		when(dateSourceService.resolveFileSystemDates(renamed)).thenReturn(new FileSystemDates(CREATED, CREATED));

		OrganizationReconcileResponse response = response(
				List.of(issue(1L, PathUtils.normalize("C:/media/original.jpg")),
						issue(2L, PathUtils.normalize("C:/media/other.jpg"))),
				List.of(issue(null, renamed.toString())));

		Assertions.assertThat(service.detectAndApplyRenames(response)).containsExactly(1L);

		Assertions.assertThat(unmatched.getFileKey()).isEqualTo("C:/media/other.jpg");
	}

	private Path write(String name, String content) throws IOException {
		return Files.writeString(dir.resolve(name), content);
	}

	private CatalogFile catalogFile(Long id, String currentPath, Long sizeBytes, String sha256) {
		CatalogFile catalogFile = CatalogFile.builder().id(id).fileKey(currentPath).fileName("original.jpg")
				.extension("jpg").sizeBytes(sizeBytes).sha256(sha256).build();

		catalogFile.setCreatedAt(CREATED);
		catalogFile.setLocation(CatalogFileLocation.builder().catalogFile(catalogFile).currentPath(currentPath)
				.currentFolder("C:/media").originalPath(currentPath).originalFolder("C:/media").build());

		return catalogFile;
	}

	private OrganizationReconcileResponse response(CatalogFile catalogFile, Path newFile) {
		return response(List.of(issue(catalogFile.getId(), PathUtils.normalize(catalogFile.getFileKey()))),
				List.of(issue(null, newFile.toString())));
	}

	private OrganizationReconcileResponse response(List<OrganizationReconcileIssueResponse> missingOnDisk,
			List<OrganizationReconcileIssueResponse> missingInDatabase) {
		return new OrganizationReconcileResponse("C:/media", true, false, 0, 0, missingOnDisk.size(),
				missingInDatabase.size(), 0, missingOnDisk, missingInDatabase, List.of(), 0, 0, 0);
	}

	private OrganizationReconcileIssueResponse issue(Long catalogFileId, String path) {
		return new OrganizationReconcileIssueResponse(catalogFileId, path, null, null, null);
	}
}