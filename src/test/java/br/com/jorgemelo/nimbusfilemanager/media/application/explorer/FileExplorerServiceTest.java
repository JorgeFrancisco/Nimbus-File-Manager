package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.media.application.dto.FileExplorerEntry;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.FileExplorerView;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.FileExplorerLocationProjection;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

class FileExplorerServiceTest {

	@TempDir
	private Path tempDir;

	@Test
	void browseShouldListCurrentFolderAndMarkDatabaseFilesMissingOnDisk() throws Exception {
		Path folder = Files.createDirectories(tempDir.resolve("photos"));
		Path childFolder = Files.createDirectories(folder.resolve("trip"));
		Path file = Files.writeString(folder.resolve("image.jpg"), "image");
		Path missing = folder.resolve("missing.jpg");

		CatalogFileLocationRepository catalogFileLocationRepository = mock(CatalogFileLocationRepository.class);
		WorkspaceManager workspaceManager = mock(WorkspaceManager.class);

		FileExplorerLocationProjection location = missingLocation(missing);

		when(catalogFileLocationRepository.findActiveByCurrentFolder(PathUtils.normalize(folder)))
				.thenReturn(List.of(location));

		FileExplorerReconcileService reconcileService = mock(FileExplorerReconcileService.class);

		FileExplorerView view = new FileExplorerService(workspaceManager, catalogFileLocationRepository,
				mock(ScanExclusionService.class), reconcileService).browse(folder.toString(), "large");

		verify(reconcileService).markMissing(List.of(10L));

		Assertions.assertThat(view.path()).isEqualTo(PathUtils.normalize(folder));
		Assertions.assertThat(view.parentPath()).isEqualTo(PathUtils.normalize(tempDir));
		Assertions.assertThat(view.viewMode()).isEqualTo("large");
		Assertions.assertThat(view.folderCount()).isEqualTo(1);
		Assertions.assertThat(view.fileCount()).isEqualTo(1);
		Assertions.assertThat(view.missingCount()).isEqualTo(1);
		Assertions.assertThat(view.entries()).extracting(FileExplorerEntry::path)
				.contains(PathUtils.normalize(childFolder), PathUtils.normalize(file), PathUtils.normalize(missing));
		Assertions.assertThat(view.entries()).filteredOn(FileExplorerEntry::image).singleElement()
				.extracting(FileExplorerEntry::previewUrl).asString().contains("/app/files/preview?path=");
		Assertions.assertThat(view.entries()).filteredOn(FileExplorerEntry::missing).singleElement()
				.extracting(FileExplorerEntry::name).isEqualTo("missing.jpg");
	}

	@Test
	void browseShouldUseWorkspaceTempWhenPathIsBlank() throws Exception {
		Path file = Files.writeString(tempDir.resolve("file.txt"), "content");

		CatalogFileLocationRepository catalogFileLocationRepository = mock(CatalogFileLocationRepository.class);
		WorkspaceManager workspaceManager = mock(WorkspaceManager.class);

		when(workspaceManager.temp()).thenReturn(tempDir);
		when(catalogFileLocationRepository.findActiveByCurrentFolder(PathUtils.normalize(tempDir)))
				.thenReturn(List.of());

		FileExplorerView view = new FileExplorerService(workspaceManager, catalogFileLocationRepository,
				mock(ScanExclusionService.class), mock(FileExplorerReconcileService.class)).browse(" ", "bad");

		Assertions.assertThat(view.path()).isEqualTo(PathUtils.normalize(tempDir));
		Assertions.assertThat(view.viewMode()).isEqualTo("details");
		Assertions.assertThat(view.entries()).extracting(FileExplorerEntry::path)
				.containsExactly(PathUtils.normalize(file));
	}

	@Test
	void browseShouldReturnMissingFolderStateWhenPathDoesNotExist() {
		Path missing = tempDir.resolve("does-not-exist");

		CatalogFileLocationRepository catalogFileLocationRepository = mock(CatalogFileLocationRepository.class);
		WorkspaceManager workspaceManager = mock(WorkspaceManager.class);

		FileExplorerView view = new FileExplorerService(workspaceManager, catalogFileLocationRepository,
				mock(ScanExclusionService.class), mock(FileExplorerReconcileService.class))
				.browse(missing.toString(), "details");

		Assertions.assertThat(view.exists()).isFalse();
		Assertions.assertThat(view.entries()).isEmpty();
	}

	@Test
	void browseShouldRepresentEmptyDirectoryWithStablePaginationDefaults() throws Exception {
		Path folder = Files.createDirectory(tempDir.resolve("empty"));

		CatalogFileLocationRepository repository = mock(CatalogFileLocationRepository.class);
		WorkspaceManager workspaceManager = mock(WorkspaceManager.class);

		when(repository.findActiveByCurrentFolder(PathUtils.normalize(folder))).thenReturn(List.of());

		FileExplorerView view = new FileExplorerService(workspaceManager, repository, mock(ScanExclusionService.class),
				mock(FileExplorerReconcileService.class)).browse(folder.toString(), null, -1, 999);

		Assertions.assertThat(view.exists()).isTrue();
		Assertions.assertThat(view.directory()).isTrue();
		Assertions.assertThat(view.entries()).isEmpty();
		Assertions.assertThat(view.page()).isZero();
		Assertions.assertThat(view.size()).isEqualTo(50);
		Assertions.assertThat(view.totalPages()).isEqualTo(1);
		Assertions.assertThat(view.hasPrevious()).isFalse();
		Assertions.assertThat(view.hasNext()).isFalse();
	}

	@Test
	void browseShouldUseWorkspaceTempWhenThePathIsOnlyWhitespace() throws Exception {
		Path temp = Files.createDirectory(tempDir.resolve("temp-blank"));

		CatalogFileLocationRepository repository = mock(CatalogFileLocationRepository.class);
		WorkspaceManager workspaceManager = mock(WorkspaceManager.class);

		when(workspaceManager.temp()).thenReturn(temp);
		when(repository.findActiveByCurrentFolder(PathUtils.normalize(temp))).thenReturn(List.of());

		FileExplorerView view = new FileExplorerService(workspaceManager, repository, mock(ScanExclusionService.class),
				mock(FileExplorerReconcileService.class)).browse("   ", null);

		Assertions.assertThat(view.path()).isEqualTo(PathUtils.normalize(temp));
	}

	/**
	 * A page size the screen actually offers is honoured as-is; only sizes outside
	 * the offered set fall back to the default.
	 */
	@Test
	void browseShouldHonourAnOfferedPageSize() throws Exception {
		Path folder = Files.createDirectory(tempDir.resolve("sized"));

		CatalogFileLocationRepository repository = mock(CatalogFileLocationRepository.class);

		when(repository.findActiveByCurrentFolder(PathUtils.normalize(folder))).thenReturn(List.of());

		FileExplorerView view = new FileExplorerService(mock(WorkspaceManager.class), repository,
				mock(ScanExclusionService.class), mock(FileExplorerReconcileService.class))
				.browse(folder.toString(), null, 0, 20);

		Assertions.assertThat(view.size()).isEqualTo(20);
	}

	/**
	 * A name with no dot, or one whose only dot is the last character, has no
	 * usable extension and is labelled generically instead of yielding an empty
	 * type.
	 */
	@Test
	void browseShouldLabelAFileWithoutAnExtensionAsAGenericFile() throws Exception {
		Path folder = Files.createDirectory(tempDir.resolve("noext"));

		Files.writeString(folder.resolve("README"), "no extension here");

		CatalogFileLocationRepository repository = mock(CatalogFileLocationRepository.class);

		when(repository.findActiveByCurrentFolder(PathUtils.normalize(folder))).thenReturn(List.of());

		FileExplorerView view = new FileExplorerService(mock(WorkspaceManager.class), repository,
				mock(ScanExclusionService.class), mock(FileExplorerReconcileService.class))
				.browse(folder.toString(), null);

		Assertions.assertThat(view.entries()).singleElement().extracting(FileExplorerEntry::fileType)
				.isEqualTo("FILE");
	}

	@Test
	void browseShouldReturnFileEntryWhenRequestedPathIsNotDirectory() throws Exception {
		Path file = Files.writeString(tempDir.resolve("movie.mp4"), "video");

		CatalogFileLocationRepository repository = mock(CatalogFileLocationRepository.class);
		WorkspaceManager workspaceManager = mock(WorkspaceManager.class);

		FileExplorerView view = new FileExplorerService(workspaceManager, repository, mock(ScanExclusionService.class),
				mock(FileExplorerReconcileService.class)).browse(file.toString(), "xlarge", 99, 20);

		Assertions.assertThat(view.exists()).isTrue();
		Assertions.assertThat(view.directory()).isFalse();
		Assertions.assertThat(view.fileCount()).isEqualTo(1);
		Assertions.assertThat(view.entries()).singleElement().satisfies(entry -> {
			Assertions.assertThat(entry.name()).isEqualTo("movie.mp4");
			Assertions.assertThat(entry.video()).isTrue();
			Assertions.assertThat(entry.previewUrl()).contains("/app/files/preview?path=");
		});

		verify(repository).findActiveByCurrentFolder(PathUtils.normalize(tempDir));
	}

	@Test
	void browseShouldFlagPdfTextAndAudioFilesAsPreviewableLikeImagesAndVideos() throws Exception {
		Path folder = Files.createDirectories(tempDir.resolve("mixed"));

		Files.writeString(folder.resolve("document.pdf"), "pdf");
		Files.writeString(folder.resolve("notes.txt"), "text");
		Files.writeString(folder.resolve("song.mp3"), "audio");
		Files.writeString(folder.resolve("archive.zip"), "zip");

		CatalogFileLocationRepository repository = mock(CatalogFileLocationRepository.class);
		WorkspaceManager workspaceManager = mock(WorkspaceManager.class);

		when(repository.findActiveByCurrentFolder(PathUtils.normalize(folder))).thenReturn(List.of());

		FileExplorerView view = new FileExplorerService(workspaceManager, repository, mock(ScanExclusionService.class),
				mock(FileExplorerReconcileService.class)).browse(folder.toString(), "details", 0, 20);

		Assertions.assertThat(view.entries()).filteredOn(entry -> entry.name().equals("document.pdf")).singleElement()
				.satisfies(entry -> {
					Assertions.assertThat(entry.pdf()).isTrue();
					Assertions.assertThat(entry.previewUrl()).contains("/app/files/preview?path=");
				});
		Assertions.assertThat(view.entries()).filteredOn(entry -> entry.name().equals("notes.txt")).singleElement()
				.satisfies(entry -> {
					Assertions.assertThat(entry.text()).isTrue();
					Assertions.assertThat(entry.previewUrl()).contains("/app/files/preview?path=");
				});
		Assertions.assertThat(view.entries()).filteredOn(entry -> entry.name().equals("song.mp3")).singleElement()
				.satisfies(entry -> {
					Assertions.assertThat(entry.audio()).isTrue();
					Assertions.assertThat(entry.previewUrl()).contains("/app/files/preview?path=");
				});
		Assertions.assertThat(view.entries()).filteredOn(entry -> entry.name().equals("archive.zip")).singleElement()
				.satisfies(entry -> {
					Assertions.assertThat(entry.pdf()).isFalse();
					Assertions.assertThat(entry.text()).isFalse();
					Assertions.assertThat(entry.audio()).isFalse();
					Assertions.assertThat(entry.previewUrl()).isNull();
				});
	}

	@Test
	void browseShouldPaginateEntries() throws Exception {
		Path folder = Files.createDirectories(tempDir.resolve("paged"));

		CatalogFileLocationRepository catalogFileLocationRepository = mock(CatalogFileLocationRepository.class);
		WorkspaceManager workspaceManager = mock(WorkspaceManager.class);

		for (int index = 0; index < 21; index++) {
			Files.writeString(folder.resolve("image-" + String.format("%02d", index) + ".jpg"), "a");
		}

		when(catalogFileLocationRepository.findActiveByCurrentFolder(PathUtils.normalize(folder)))
				.thenReturn(List.of());

		FileExplorerView view = new FileExplorerService(workspaceManager, catalogFileLocationRepository,
				mock(ScanExclusionService.class), mock(FileExplorerReconcileService.class))
				.browse(folder.toString(), "small", 1, 20);

		Assertions.assertThat(view.page()).isEqualTo(1);
		Assertions.assertThat(view.size()).isEqualTo(20);
		Assertions.assertThat(view.totalItems()).isEqualTo(21);
		Assertions.assertThat(view.totalPages()).isEqualTo(2);
		Assertions.assertThat(view.hasPrevious()).isTrue();
		Assertions.assertThat(view.hasNext()).isFalse();
		Assertions.assertThat(view.entries()).hasSize(1);
	}

	@Test
	void browseShouldClampPagesAtBeginningMiddleAndEnd() throws Exception {
		Path folder = Files.createDirectory(tempDir.resolve("pages"));

		for (int index = 0; index < 41; index++) {
			Files.writeString(folder.resolve(String.format("%02d.jpg", index)), "x");
		}

		CatalogFileLocationRepository repository = mock(CatalogFileLocationRepository.class);
		WorkspaceManager workspaceManager = mock(WorkspaceManager.class);

		when(repository.findActiveByCurrentFolder(PathUtils.normalize(folder))).thenReturn(List.of());

		FileExplorerService service = new FileExplorerService(workspaceManager, repository,
				mock(ScanExclusionService.class), mock(FileExplorerReconcileService.class));

		FileExplorerView first = service.browse(folder.toString(), "details", null, 20);
		FileExplorerView middle = service.browse(folder.toString(), "details", 1, 20);
		FileExplorerView beyondEnd = service.browse(folder.toString(), "details", 99, 20);

		Assertions.assertThat(first.page()).isZero();
		Assertions.assertThat(first.hasNext()).isTrue();
		Assertions.assertThat(first.entries()).hasSize(20);
		Assertions.assertThat(middle.page()).isEqualTo(1);
		Assertions.assertThat(middle.hasPrevious()).isTrue();
		Assertions.assertThat(middle.hasNext()).isTrue();
		Assertions.assertThat(middle.entries()).hasSize(20);
		Assertions.assertThat(beyondEnd.page()).isEqualTo(2);
		Assertions.assertThat(beyondEnd.hasPrevious()).isTrue();
		Assertions.assertThat(beyondEnd.hasNext()).isFalse();
		Assertions.assertThat(beyondEnd.entries()).hasSize(1);
	}

	@Test
	void browseShouldIgnoreDuplicateMissingDatabaseRowsAndKeepDeterministicOrdering() throws Exception {
		Path folder = Files.createDirectory(tempDir.resolve("known"));
		Path present = Files.writeString(folder.resolve("b.jpg"), "b");
		Path missing = folder.resolve("a.jpg");

		CatalogFileLocationRepository repository = mock(CatalogFileLocationRepository.class);
		WorkspaceManager workspaceManager = mock(WorkspaceManager.class);

		FileExplorerLocationProjection first = missingLocation(missing);
		FileExplorerLocationProjection duplicate = missingLocation(missing);

		when(repository.findActiveByCurrentFolder(PathUtils.normalize(folder))).thenReturn(List.of(first, duplicate));

		FileExplorerReconcileService reconcileService = mock(FileExplorerReconcileService.class);

		FileExplorerView view = new FileExplorerService(workspaceManager, repository, mock(ScanExclusionService.class),
				reconcileService).browse(folder.toString(), "details", 0, 20);

		verify(reconcileService).markMissing(List.of(10L));

		Assertions.assertThat(view.missingCount()).isEqualTo(1);
		Assertions.assertThat(view.entries()).extracting(FileExplorerEntry::name).containsExactly("a.jpg", "b.jpg");
		Assertions.assertThat(view.entries()).filteredOn(FileExplorerEntry::missing).singleElement()
				.extracting(FileExplorerEntry::path).isEqualTo(PathUtils.normalize(missing));
		Assertions.assertThat(view.entries()).filteredOn(entry -> !entry.missing()).singleElement()
				.extracting(FileExplorerEntry::path).isEqualTo(PathUtils.normalize(present));
	}

	@Test
	void browseShouldApplyNameAndDateSortDirections() throws Exception {
		Path folder = Files.createDirectory(tempDir.resolve("sorted"));
		Path alpha = Files.writeString(folder.resolve("alpha.txt"), "a");
		Path beta = Files.writeString(folder.resolve("beta.txt"), "b");

		Files.setLastModifiedTime(alpha, FileTime.fromMillis(1_000));
		Files.setLastModifiedTime(beta, FileTime.fromMillis(2_000));

		CatalogFileLocationRepository repository = mock(CatalogFileLocationRepository.class);
		WorkspaceManager workspaceManager = mock(WorkspaceManager.class);

		when(repository.findActiveByCurrentFolder(PathUtils.normalize(folder))).thenReturn(List.of());

		FileExplorerService service = new FileExplorerService(workspaceManager, repository,
				mock(ScanExclusionService.class), mock(FileExplorerReconcileService.class));

		Assertions.assertThat(service.browse(folder.toString(), "details", 0, 20, "name").entries())
				.extracting(FileExplorerEntry::name).containsExactly("alpha.txt", "beta.txt");
		Assertions.assertThat(service.browse(folder.toString(), "details", 0, 20, "name-desc").entries())
				.extracting(FileExplorerEntry::name).containsExactly("beta.txt", "alpha.txt");
		Assertions.assertThat(service.browse(folder.toString(), "details", 0, 20, "date-oldest").entries())
				.extracting(FileExplorerEntry::name).containsExactly("alpha.txt", "beta.txt");
		Assertions.assertThat(service.browse(folder.toString(), "details", 0, 20, "date-newest").entries())
				.extracting(FileExplorerEntry::name).containsExactly("beta.txt", "alpha.txt");
	}

	@Test
	void browseShouldHideExcludedDiskAndDatabaseEntries() throws Exception {
		Path folder = Files.createDirectories(tempDir.resolve("files"));
		Path visible = Files.writeString(folder.resolve("visible.jpg"), "image");
		Path git = Files.createDirectory(folder.resolve(".git"));
		Path ignoredDisk = Files.writeString(git.resolve("config"), "git");
		Path ignoredMissing = git.resolve("missing.jpg");

		CatalogFileLocationRepository catalogFileLocationRepository = mock(CatalogFileLocationRepository.class);
		WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
		ScanExclusionService scanExclusionService = mock(ScanExclusionService.class);

		FileExplorerLocationProjection ignoredLocation = missingLocation(ignoredMissing);

		when(scanExclusionService.isExcluded(folder, ignoredDisk)).thenReturn(true);
		when(scanExclusionService.isExcluded(folder, git)).thenReturn(true);
		when(scanExclusionService.isExcluded(folder, ignoredMissing)).thenReturn(true);
		when(catalogFileLocationRepository.findActiveByCurrentFolder(PathUtils.normalize(folder)))
				.thenReturn(List.of(ignoredLocation));

		FileExplorerView view = new FileExplorerService(workspaceManager, catalogFileLocationRepository,
				scanExclusionService, mock(FileExplorerReconcileService.class)).browse(folder.toString(), "large");

		Assertions.assertThat(view.entries()).extracting(FileExplorerEntry::path)
				.containsExactly(PathUtils.normalize(visible));
		Assertions.assertThat(view.missingCount()).isZero();
	}

	@Test
	void browseShouldFlagDiskFilesThatAreAlreadyRegisteredInDatabase() throws Exception {
		Path folder = Files.createDirectories(tempDir.resolve("registered"));
		Path inventoried = Files.writeString(folder.resolve("inventoried.jpg"), "a");

		Files.writeString(folder.resolve("not-inventoried.jpg"), "b");

		CatalogFileLocationRepository catalogFileLocationRepository = mock(CatalogFileLocationRepository.class);
		WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
		FileExplorerLocationProjection location = mock(FileExplorerLocationProjection.class);

		when(location.getCurrentPath()).thenReturn(inventoried.toString());
		when(catalogFileLocationRepository.findActiveByCurrentFolder(PathUtils.normalize(folder)))
				.thenReturn(List.of(location));

		FileExplorerView view = new FileExplorerService(workspaceManager, catalogFileLocationRepository,
				mock(ScanExclusionService.class), mock(FileExplorerReconcileService.class))
				.browse(folder.toString(), "details");

		Assertions.assertThat(view.entries()).filteredOn(entry -> entry.name().equals("inventoried.jpg"))
				.singleElement().extracting(FileExplorerEntry::registered).isEqualTo(true);
		Assertions.assertThat(view.entries()).filteredOn(entry -> entry.name().equals("not-inventoried.jpg"))
				.singleElement().extracting(FileExplorerEntry::registered).isEqualTo(false);
		Assertions.assertThat(view.missingCount()).isZero();
		Assertions.assertThat(view.inventoriedCount()).isEqualTo(1);
	}

	@Test
	void catalogedOtherWebpUsesGenericIconWithoutRequestingThumbnail() throws Exception {
		Path folder = Files.createDirectories(tempDir.resolve("stickers"));
		Path sticker = Files.write(folder.resolve("lottie.webp"), new byte[] { 'P', 'K', 3, 4 });

		CatalogFileLocationRepository repository = mock(CatalogFileLocationRepository.class);
		FileExplorerLocationProjection location = mock(FileExplorerLocationProjection.class);

		when(location.getCurrentPath()).thenReturn(sticker.toString());
		when(location.getFileType()).thenReturn(FileType.OTHER);
		when(repository.findActiveByCurrentFolder(PathUtils.normalize(folder))).thenReturn(List.of(location));

		FileExplorerView view = new FileExplorerService(mock(WorkspaceManager.class), repository,
				mock(ScanExclusionService.class), mock(FileExplorerReconcileService.class))
				.browse(folder.toString(), "large");

		Assertions.assertThat(view.entries()).singleElement().satisfies(entry -> {
			Assertions.assertThat(entry.registered()).isTrue();
			Assertions.assertThat(entry.fileType()).isEqualTo("OTHER");
			Assertions.assertThat(entry.image()).isFalse();
			Assertions.assertThat(entry.previewUrl()).isNull();
			Assertions.assertThat(entry.iconClass()).contains("generic");
		});
	}

	@Test
	void entryShouldExposeIconClassAndLabelByFileType() {
		FileExplorerEntry pdf = entry("PDF");
		FileExplorerEntry docx = entry("DOCX");
		FileExplorerEntry rar = entry("RAR");

		Assertions.assertThat(pdf.iconClass()).isEqualTo("bi-file-earmark-pdf-fill pdf");
		Assertions.assertThat(pdf.iconLabelKey()).isEqualTo("filetype.pdf");
		Assertions.assertThat(docx.iconClass()).isEqualTo("bi-file-earmark-word-fill word");
		Assertions.assertThat(docx.iconLabelKey()).isEqualTo("filetype.word");
		Assertions.assertThat(rar.iconClass()).isEqualTo("bi-file-earmark-zip-fill archive");
		Assertions.assertThat(rar.iconLabelKey()).isEqualTo("filetype.rar");
	}

	@Test
	void availableDrivesShouldReturnNormalizedFilesystemRoots() {
		CatalogFileLocationRepository catalogFileLocationRepository = mock(CatalogFileLocationRepository.class);
		WorkspaceManager workspaceManager = mock(WorkspaceManager.class);

		List<String> drives = new FileExplorerService(workspaceManager, catalogFileLocationRepository,
				mock(ScanExclusionService.class), mock(FileExplorerReconcileService.class)).availableDrives();

		Assertions.assertThat(drives).isNotEmpty()
				.allSatisfy(drive -> Assertions.assertThat(drive).isNotBlank());
	}

	/**
	 * Opening a single file straight from a link: the folder it lives in is read so
	 * the screen can say whether that file is in the catalog. With an empty folder
	 * behind the mock, the registered case had never run.
	 */
	@Test
	void browseShouldMarkASingleRequestedFileAsRegisteredWhenTheCatalogKnowsIt() throws Exception {
		Path file = Files.writeString(tempDir.resolve("known.jpg"), "image");

		CatalogFileLocationRepository repository = mock(CatalogFileLocationRepository.class);

		FileExplorerLocationProjection location = mock(FileExplorerLocationProjection.class);

		UUID mediaId = UUID.randomUUID();

		when(location.getCatalogFileId()).thenReturn(77L);
		when(location.getPublicId()).thenReturn(mediaId);
		when(location.getFileName()).thenReturn("known.jpg");
		when(location.getFileType()).thenReturn(FileType.PHOTO);
		when(location.getSizeBytes()).thenReturn(5L);
		when(location.getCurrentPath()).thenReturn(file.toString());

		when(repository.findActiveByCurrentFolder(PathUtils.normalize(tempDir))).thenReturn(List.of(location));

		FileExplorerView view = new FileExplorerService(mock(WorkspaceManager.class), repository,
				mock(ScanExclusionService.class), mock(FileExplorerReconcileService.class))
						.browse(file.toString(), "large");

		Assertions.assertThat(view.directory()).isFalse();
		Assertions.assertThat(view.inventoriedCount()).isEqualTo(1);
		Assertions.assertThat(view.entries()).singleElement().satisfies(entry -> {
			Assertions.assertThat(entry.registered()).isTrue();
			Assertions.assertThat(entry.mediaPublicId()).isEqualTo(mediaId);
			Assertions.assertThat(entry.fileType()).isEqualTo(FileType.PHOTO.name());
		});
	}

	private FileExplorerLocationProjection missingLocation(Path missing) {
		FileExplorerLocationProjection location = mock(FileExplorerLocationProjection.class);

		when(location.getCatalogFileId()).thenReturn(10L);
		when(location.getFileName()).thenReturn("missing.jpg");
		when(location.getFileType()).thenReturn(FileType.PHOTO);
		when(location.getSizeBytes()).thenReturn(42L);
		when(location.getCurrentPath()).thenReturn(missing.toString());

		return location;
	}

	private FileExplorerEntry entry(String fileType) {
		return new FileExplorerEntry("file", "C:/file", false, false, false, fileType, false, false, false, false,
				false, null, 1L, null, null, null, null);
	}
}