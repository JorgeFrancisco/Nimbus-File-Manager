package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

import br.com.jorgemelo.nimbusfilemanager.execution.application.GrantingOperationLocks;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.date.DateSourceService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.FileHashes;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.FileSystemDates;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileIssueResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileRequest;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.EligibilityAnnouncer;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.projection.MediaLocationReconcileProjection;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.application.catalog.CollectionCatalogMutations;

@ExtendWith(MockitoExtension.class)
class OrganizationReconcileServiceTest {

	@TempDir
	Path tempDir;

	@Mock
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Mock
	private ScanExclusionService scanExclusionService;

	@Mock
	private EligibilityAnnouncer eligibilityAnnouncer;

	@Mock
	private CatalogFileRepository catalogFileRepository;

	@Mock
	private FileHashService fileHashService;

	@Mock
	private DateSourceService dateSourceService;

	private final OperationLockService operationLockService = GrantingOperationLocks.granting();

	@Test
	void reconcileAndApplyShouldDetectAndMergeUnambiguousRename() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path oldPath = source.resolve("old-name.jpg");
		Path newPath = Files.writeString(source.resolve("new-name.jpg"), "hello");

		CatalogFile catalogFile = catalogFileWithLocation(1L, oldPath, 5L, "sha-a",
				LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0));

		when(catalogFileLocationRepository.findForReconcile(eq(source.toAbsolutePath().normalize().toString()), any(),
				eq(0L), any(Limit.class))).thenReturn(List.of(row(1L, oldPath, oldPath)));
		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L))).thenReturn(List.of(catalogFile));
		when(fileHashService.hashes(newPath.toAbsolutePath().normalize())).thenReturn(new FileHashes("sha-a", "md5-a"));

		applyingPass().reconcileAndApply(new OrganizationReconcileRequest(source.toString(), true, false, 10));

		Assertions.assertThat(catalogFile.getFileKey()).isEqualTo(newPath.toAbsolutePath().normalize().toString());
		Assertions.assertThat(catalogFile.isActive()).isTrue();
		Assertions.assertThat(catalogFile.getLocation().getCurrentPath())
				.isEqualTo(newPath.toAbsolutePath().normalize().toString());

		verify(catalogFileRepository, never()).markMissingByIds(any(), any());
	}

	@Test
	void reconcileAndApplyShouldNotMergeWhenSizeDiffers() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path oldPath = source.resolve("old-name.jpg");

		Files.writeString(source.resolve("unrelated.jpg"), "a much longer content than the original");

		CatalogFile catalogFile = catalogFileWithLocation(1L, oldPath, 5L, "sha-a",
				LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0));

		when(catalogFileLocationRepository.findForReconcile(eq(source.toAbsolutePath().normalize().toString()), any(),
				eq(0L), any(Limit.class))).thenReturn(List.of(row(1L, oldPath, oldPath)));
		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L))).thenReturn(List.of(catalogFile));

		applyingPass().reconcileAndApply(new OrganizationReconcileRequest(source.toString(), true, false, 10));

		Assertions.assertThat(catalogFile.getFileKey()).isEqualTo(oldPath.toAbsolutePath().normalize().toString());

		verify(catalogFileRepository).markMissingByIds(eq(new Long[] { 1L }), any());
	}

	/**
	 * Marking a file missing takes it out of the set a duplicate analysis looks at,
	 * which is the one thing about a reconcile pass that is an eligibility change
	 * outright - no folder and no exclusion enters into it. Announced once for the
	 * pass, however many files it marked.
	 */
	@Test
	void aPassThatMarkedFilesMissingAsksForOneRegroup() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path oldPath = source.resolve("old-name.jpg");

		Files.writeString(source.resolve("unrelated.jpg"), "a much longer content than the original");

		when(catalogFileRepository.markMissingByIds(any(), any())).thenReturn(1);
		when(catalogFileLocationRepository.findForReconcile(eq(source.toAbsolutePath().normalize().toString()), any(),
				eq(0L), any(Limit.class))).thenReturn(List.of(row(1L, oldPath, oldPath)));
		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L)))
				.thenReturn(List.of(catalogFileWithLocation(1L, oldPath, 5L, "sha-a",
						LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0))));

		applyingPass().reconcileAndApply(new OrganizationReconcileRequest(source.toString(), true, false, 10));

		verify(eligibilityAnnouncer).announce("reconcile");
	}

	/**
	 * The pass that agreed with the disk, which is nearly every one of them:
	 * nothing was renamed, repaired or marked, so there is nothing to bring up to
	 * date.
	 */
	@Test
	void aPassThatFoundNothingToCorrectAsksForNothing() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));

		Files.writeString(source.resolve("photo.jpg"), "content");

		when(catalogFileLocationRepository.findForReconcile(eq(source.toAbsolutePath().normalize().toString()), any(),
				eq(0L), any(Limit.class))).thenReturn(List.of());

		applyingPass().reconcileAndApply(new OrganizationReconcileRequest(source.toString(), true, false, 10));

		verifyNoInteractions(eligibilityAnnouncer);
	}

	@Test
	void reconcileAndApplyShouldNotMergeAmbiguousMatchesWithoutCreationTime() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path oldA = source.resolve("old-a.jpg");
		Path oldB = source.resolve("old-b.jpg");

		Files.writeString(source.resolve("new-a.jpg"), "");
		Files.writeString(source.resolve("new-b.jpg"), "");

		CatalogFile catalogFileA = catalogFileWithLocation(1L, oldA, 0L, "sha-empty", null);
		CatalogFile catalogFileB = catalogFileWithLocation(2L, oldB, 0L, "sha-empty", null);

		when(catalogFileLocationRepository.findForReconcile(eq(source.toAbsolutePath().normalize().toString()), any(),
				eq(0L), any(Limit.class))).thenReturn(List.of(row(1L, oldA, oldA), row(2L, oldB, oldB)));
		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L, 2L)))
				.thenReturn(List.of(catalogFileA, catalogFileB));
		when(fileHashService.hashes(any())).thenReturn(new FileHashes("sha-empty", "md5-empty"));

		applyingPass().reconcileAndApply(new OrganizationReconcileRequest(source.toString(), true, false, 10));

		Assertions.assertThat(catalogFileA.getFileKey()).isEqualTo(oldA.toAbsolutePath().normalize().toString());
		Assertions.assertThat(catalogFileB.getFileKey()).isEqualTo(oldB.toAbsolutePath().normalize().toString());

		verify(catalogFileRepository).markMissingByIds(eq(new Long[] { 1L, 2L }), any());
	}

	@Test
	void reconcileAndApplyShouldUseCreationTimeToResolveHashCollision() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path oldA = source.resolve("old-a.jpg");
		Path oldB = source.resolve("old-b.jpg");
		Path newA = Files.writeString(source.resolve("new-a.jpg"), "");
		Path newB = Files.writeString(source.resolve("new-b.jpg"), "");

		LocalDateTime createdAtA = LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0);
		LocalDateTime createdAtB = LocalDateTime.of(2024, Month.JUNE, 1, 8, 30);

		CatalogFile catalogFileA = catalogFileWithLocation(1L, oldA, 0L, "sha-empty", createdAtA);
		CatalogFile catalogFileB = catalogFileWithLocation(2L, oldB, 0L, "sha-empty", createdAtB);

		when(catalogFileLocationRepository.findForReconcile(eq(source.toAbsolutePath().normalize().toString()), any(),
				eq(0L), any(Limit.class))).thenReturn(List.of(row(1L, oldA, oldA), row(2L, oldB, oldB)));
		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L, 2L)))
				.thenReturn(List.of(catalogFileA, catalogFileB));
		when(fileHashService.hashes(any())).thenReturn(new FileHashes("sha-empty", "md5-empty"));
		when(dateSourceService.resolveFileSystemDates(newA.toAbsolutePath().normalize()))
				.thenReturn(new FileSystemDates(createdAtA, createdAtA));
		when(dateSourceService.resolveFileSystemDates(newB.toAbsolutePath().normalize()))
				.thenReturn(new FileSystemDates(createdAtB, createdAtB));

		applyingPass().reconcileAndApply(new OrganizationReconcileRequest(source.toString(), true, false, 10));

		Assertions.assertThat(catalogFileA.getFileKey()).isEqualTo(newA.toAbsolutePath().normalize().toString());
		Assertions.assertThat(catalogFileB.getFileKey()).isEqualTo(newB.toAbsolutePath().normalize().toString());

		verify(catalogFileRepository, never()).markMissingByIds(any(), any());
	}

	@Test
	void reconcileAndApplyShouldSyncStaleCurrentPathToFileKeyWhenTargetExistsOnDisk() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path organized = Files.writeString(source.resolve("organized.jpg"), "organized-content");
		Path phantom = source.resolve("phantom.jpg");

		// catalog_file 3: file_key already points at the real (moved) file on disk, but
		// current_path still lags on the stale phantom path that no longer exists.
		when(catalogFileLocationRepository.findForReconcile(eq(source.toAbsolutePath().normalize().toString()), any(),
				eq(0L), any(Limit.class))).thenReturn(List.of(row(3L, organized, phantom)));
		// Different size -> rename detection cannot content-match, so the
		// file_key-based
		// repair (not the rename merge) is what must fix it.
		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(3L))).thenReturn(List.of(
				catalogFileWithLocation(3L, phantom, 5L, "sha-x", LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0))));

		CatalogFileLocation location = catalogFileWithLocation(3L, phantom, 5L, "sha-x",
				LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0)).getLocation();

		when(catalogFileLocationRepository.findById(3L)).thenReturn(Optional.of(location));

		applyingPass().reconcileAndApply(new OrganizationReconcileRequest(source.toString(), true, false, 10));

		Assertions.assertThat(location.getCurrentPath()).isEqualTo(organized.toAbsolutePath().normalize().toString());

		verify(catalogFileRepository, never()).markMissingByIds(any(), any());
	}

	@Test
	void reconcileShouldReportMissingDiskDatabaseAndPathMismatch() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path ok = Files.writeString(source.resolve("ok.jpg"), "ok");
		Path onlyDisk = Files.writeString(source.resolve("only-disk.jpg"), "disk");
		Path missingDisk = source.resolve("missing.jpg");

		when(catalogFileLocationRepository.findForReconcile(eq(source.toAbsolutePath().normalize().toString()), any(),
				eq(0L), any(Limit.class)))
						.thenReturn(List.of(row(1L, ok, ok), row(2L, missingDisk, missingDisk),
								row(3L, source.resolve("different-key.jpg"), ok)));

		var response = service().reconcile(new OrganizationReconcileRequest(source.toString(), true, false, 10));

		Assertions.assertThat(response.filesOnDisk()).isEqualTo(2);
		Assertions.assertThat(response.filesInDatabase()).isEqualTo(2);
		Assertions.assertThat(response.missingOnDisk()).isEqualTo(1);
		Assertions.assertThat(response.missingInDatabase()).isEqualTo(1);
		Assertions.assertThat(response.pathMismatches()).isEqualTo(1);
		Assertions.assertThat(response.missingOnDiskSamples().getFirst().catalogFileId()).isEqualTo(2L);
		Assertions.assertThat(response.missingInDatabaseSamples().getFirst().path())
				.isEqualTo(onlyDisk.toAbsolutePath().normalize().toString());
		Assertions.assertThat(response.pathMismatchSamples().getFirst().catalogFileId()).isEqualTo(3L);
	}

	/**
	 * The straggler a move leaves behind: file_key already points at the real file
	 * while current_path still names where it used to be. Nothing is missing on
	 * disk, so the only sane repair is to let current_path catch up - and the
	 * repair has to be applied, not just counted.
	 */
	@Test
	void reconcileAndApplyShouldSyncACurrentPathThatLaggedBehindTheFileKey() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path moved = Files.writeString(source.resolve("moved.jpg"), "content");
		Path stale = source.resolve("where-it-used-to-be.jpg");

		CatalogFile catalogFile = catalogFileWithLocation(8L, stale, 7L, "sha", null);

		when(catalogFileLocationRepository.findForReconcile(eq(source.toAbsolutePath().normalize().toString()), any(),
				eq(0L), any(Limit.class))).thenReturn(List.of(row(8L, moved, stale)));
		when(catalogFileLocationRepository.findById(8L)).thenReturn(Optional.of(catalogFile.getLocation()));

		applyingPass().reconcileAndApply(new OrganizationReconcileRequest(source.toString(), true, false, 10));

		Assertions.assertThat(catalogFile.getLocation().getCurrentPath())
				.isEqualTo(moved.toAbsolutePath().normalize().toString());
	}

	/**
	 * The non-recursive scan is a different code path from the walk, and with an
	 * empty folder its filters never ran: a file in the folder has to be seen, and
	 * one in a subfolder has to be ignored.
	 */
	@Test
	void reconcileShouldSeeOnlyTheTopFolderWhenItIsNotRecursive() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));

		Files.writeString(source.resolve("top.jpg"), "top");
		Files.writeString(Files.createDirectory(source.resolve("sub")).resolve("deep.jpg"), "deep");

		var response = service().reconcile(new OrganizationReconcileRequest(source.toString(), false, false, 10));

		Assertions.assertThat(response.filesOnDisk()).isEqualTo(1);
		Assertions.assertThat(response.missingInDatabaseSamples()).singleElement()
				.satisfies(sample -> Assertions.assertThat(sample.path()).endsWith("top.jpg"));
	}

	@Test
	void reconcileShouldLimitSamples() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path missingA = source.resolve("a.jpg");
		Path missingB = source.resolve("b.jpg");

		when(catalogFileLocationRepository.findForReconcile(eq(source.toAbsolutePath().normalize().toString()), any(),
				eq(0L), any(Limit.class)))
						.thenReturn(List.of(row(1L, missingA, missingA), row(2L, missingB, missingB)));

		var response = service().reconcile(new OrganizationReconcileRequest(source.toString(), true, false, 1));

		Assertions.assertThat(response.missingOnDisk()).isEqualTo(2);
		Assertions.assertThat(response.missingOnDiskSamples()).hasSize(1);
	}

	@Test
	void reconcileShouldIgnoreExcludedDiskAndDatabasePaths() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path git = Files.createDirectory(source.resolve(".git"));
		Path ignoredDisk = Files.writeString(git.resolve("config"), "git");
		Path ignoredDatabase = git.resolve("index");
		Path visible = Files.writeString(source.resolve("visible.jpg"), "ok");

		when(scanExclusionService.isExcluded(any(Path.class), any(Path.class))).thenAnswer(invocation -> {
			Path root = invocation.getArgument(0, Path.class);
			Path candidate = invocation.getArgument(1, Path.class);

			return root.toAbsolutePath().normalize().equals(source.toAbsolutePath().normalize())
					&& candidate.toAbsolutePath().normalize().startsWith(git.toAbsolutePath().normalize());
		});
		when(catalogFileLocationRepository.findForReconcile(eq(source.toAbsolutePath().normalize().toString()), any(),
				eq(0L), any(Limit.class)))
						.thenReturn(List.of(row(1L, visible, visible), row(2L, ignoredDatabase, ignoredDatabase)));

		var response = service().reconcile(new OrganizationReconcileRequest(source.toString(), true, false, 10));

		Assertions.assertThat(response.filesOnDisk()).isEqualTo(1);
		Assertions.assertThat(response.filesInDatabase()).isEqualTo(1);
		Assertions.assertThat(response.missingInDatabaseSamples()).extracting(OrganizationReconcileIssueResponse::path)
				.doesNotContain(ignoredDisk.toAbsolutePath().normalize().toString());
	}

	@Test
	void reconcileShouldNotDescendIntoHiddenDirectoriesWhenHiddenContentIsExcluded() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path visible = Files.writeString(source.resolve("visible.jpg"), "ok");
		Path hidden = Files.createDirectory(source.resolve(".hidden"));
		Files.writeString(hidden.resolve("inside.jpg"), "hidden");
		markHidden(hidden);

		when(catalogFileLocationRepository.findForReconcile(eq(source.toAbsolutePath().normalize().toString()), any(),
				eq(0L), any(Limit.class))).thenReturn(List.of(row(1L, visible, visible)));

		var response = service().reconcile(new OrganizationReconcileRequest(source.toString(), true, false, 10));

		Assertions.assertThat(response.filesOnDisk()).isEqualTo(1);
		Assertions.assertThat(response.missingInDatabase()).isZero();
	}

	@Test
	void reconcileShouldSkipTheQuarantineSubtreeDuringRecursiveWalk() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path visible = Files.writeString(source.resolve("visible.jpg"), "ok");
		Path quarantine = Files.createDirectory(source.resolve("quarantine"));
		Files.writeString(quarantine.resolve("dupe.jpg"), "dupe");

		when(scanExclusionService.isWithinQuarantine(any(Path.class)))
				.thenAnswer(invocation -> invocation.getArgument(0, Path.class).toAbsolutePath().normalize()
						.startsWith(quarantine.toAbsolutePath().normalize()));
		when(catalogFileLocationRepository.findForReconcile(eq(source.toAbsolutePath().normalize().toString()), any(),
				eq(0L), any(Limit.class))).thenReturn(List.of(row(1L, visible, visible)));

		var response = service().reconcile(new OrganizationReconcileRequest(source.toString(), true, false, 10));

		Assertions.assertThat(response.filesOnDisk()).isEqualTo(1);
		Assertions.assertThat(response.missingInDatabase()).isZero();
	}

	@Test
	void reconcileShouldRejectMissingOrNonDirectorySourcesBeforeQueryingDatabase() {
		Path missing = tempDir.resolve("missing");
		Path file = tempDir.resolve("file.txt");

		OrganizationReconcileService service = service();

		OrganizationReconcileRequest missingRequest = request(missing);

		Assertions.assertThatThrownBy(() -> service.reconcile(missingRequest))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not exist");

		try {
			Files.writeString(file, "file");
		} catch (Exception exception) {
			throw new AssertionError(exception);
		}

		OrganizationReconcileRequest fileRequest = request(file);

		Assertions.assertThatThrownBy(() -> service.reconcile(fileRequest)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("not a directory");

		Mockito.verifyNoInteractions(catalogFileLocationRepository);
	}

	/**
	 * The walk is keyed rather than numbered: every round asks for what comes after
	 * the last id it read, and an empty round is what ends it. Two rounds carrying
	 * the same path still count as one file in the database.
	 */
	@Test
	void reconcileShouldReadEveryKeyedPageAndDeduplicatePaths() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("paged"));
		Path missing = source.resolve("missing.jpg");

		String normalized = source.toAbsolutePath().normalize().toString();

		// Shallow, so the pages come from the shallow query: the paging is the same
		// walk either way, and asking the recursive one here would be asking a
		// question this request never asks.
		when(catalogFileLocationRepository.findForShallowReconcile(eq(normalized), eq(0L), any(Limit.class)))
				.thenReturn(List.of(row(1L, missing, missing)));
		when(catalogFileLocationRepository.findForShallowReconcile(eq(normalized), eq(1L), any(Limit.class)))
				.thenReturn(List.of(row(2L, missing, missing)));

		var response = service().reconcile(new OrganizationReconcileRequest(source.toString(), false, false, 10));

		Assertions.assertThat(response.filesInDatabase()).isEqualTo(1);
		Assertions.assertThat(response.missingOnDisk()).isEqualTo(2);
		Assertions.assertThat(response.missingOnDiskSamples()).hasSize(2);
	}

	@Test
	void reconcileAndApplyIsDeferredWithoutTouchingTheCatalogWhenTheTreeIsLocked() {
		Path source = tempDir.resolve("source");

		OperationLockService lockService = mock(OperationLockService.class);

		when(lockService.acquire(any(), any())).thenThrow(new OperationLockException("busy"));

		OrganizationReconcileApply locked = new OrganizationReconcileApply(service(), lockService, applier());

		OrganizationReconcileResponse response = locked
				.reconcileAndApply(new OrganizationReconcileRequest(source.toString(), false, false, 10));

		// Deferred: nothing was scanned and nothing in the catalog was mutated.
		Assertions.assertThat(response.filesOnDisk()).isZero();
		Assertions.assertThat(response.missingOnDisk()).isZero();

		verify(catalogFileRepository, never()).markMissingByIds(any(), any());
		verify(catalogFileLocationRepository, never()).findForReconcile(any(), any(), anyLong(), any());
	}

	private void markHidden(Path directory) {
		try {
			Files.setAttribute(directory, "dos:hidden", Boolean.TRUE);
		} catch (UnsupportedOperationException | IllegalArgumentException | IOException _) {
			// Non-DOS file systems (for example POSIX) already treat the dot-prefixed name
			// as hidden, so no explicit attribute is needed there.
		}
	}

	private OrganizationReconcileRequest request(Path path) {
		return new OrganizationReconcileRequest(path.toString(), false, false, 10);
	}

	private OrganizationReconcileService service() {
		return new OrganizationReconcileService(catalogFileLocationRepository, scanExclusionService);
	}

	/** The applying pass, which is worker-side and holds the applier. */
	private OrganizationReconcileApply applyingPass() {
		return new OrganizationReconcileApply(service(), operationLockService, applier());
	}

	private ReconcileApplier applier() {
		return new ReconcileApplier(
				new OrganizationRenameDetectionService(catalogFileRepository, fileHashService, dateSourceService,
						Clock.systemDefaultZone()),
				catalogFileLocationRepository,
				new CollectionCatalogMutations(catalogFileRepository, catalogFileLocationRepository),
				eligibilityAnnouncer, Clock.systemDefaultZone());
	}

	private CatalogFile catalogFileWithLocation(Long id, Path currentPath, long sizeBytes, String sha256,
			LocalDateTime createdAt) {
		String normalizedPath = currentPath.toAbsolutePath().normalize().toString();
		String normalizedFolder = currentPath.toAbsolutePath().normalize().getParent().toString();

		CatalogFile catalogFile = CatalogFile.builder().id(id).fileKey(normalizedPath)
				.fileName(currentPath.getFileName().toString()).extension("jpg").sizeBytes(sizeBytes).sha256(sha256)
				.createdAt(createdAt).modifiedAt(createdAt != null ? createdAt : LocalDateTime.now())
				.fileType(FileType.PHOTO).lifecycleStatus(LifecycleStatus.MISSING).build();

		CatalogFileLocation location = CatalogFileLocation.builder().catalogFile(catalogFile)
				.currentPath(normalizedPath).currentFolder(normalizedFolder).originalPath(normalizedPath)
				.originalFolder(normalizedFolder).updatedAt(LocalDateTime.now()).build();

		catalogFile.setLocation(location);

		return catalogFile;
	}

	private MediaLocationReconcileProjection row(Long catalogFileId, Path fileKey, Path currentPath) {
		return new MediaLocationReconcileProjection() {

			@Override
			public Long getCatalogFileId() {
				return catalogFileId;
			}

			@Override
			public String getFileKey() {
				return fileKey.toAbsolutePath().normalize().toString();
			}

			@Override
			public String getCurrentPath() {
				return currentPath.toAbsolutePath().normalize().toString();
			}
		};
	}
}