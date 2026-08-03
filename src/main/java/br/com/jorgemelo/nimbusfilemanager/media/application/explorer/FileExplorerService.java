package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;

import br.com.jorgemelo.nimbusfilemanager.media.application.dto.BrowsedFolder;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.CatalogedFile;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.DiskListing;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.FileExplorerEntry;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.FileExplorerView;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.FolderDatabaseState;
import br.com.jorgemelo.nimbusfilemanager.media.domain.repository.projection.FileExplorerLocationProjection;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import br.com.jorgemelo.nimbusfilemanager.shared.util.DateTimeFormatUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.FilePreviewSupport;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;
import br.com.jorgemelo.nimbusfilemanager.shared.util.enums.Kind;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@Transactional(readOnly = true)
public class FileExplorerService {

	private static final Set<String> VIEW_MODES = Set.of("details", "small", "large", "xlarge");
	public static final List<Integer> PAGE_SIZES = List.of(20, 50, 100);

	private final WorkspaceManager workspaceManager;
	private final CatalogFileLocationRepository catalogFileLocationRepository;
	private final ScanExclusionService scanExclusionService;
	private final FileExplorerReconcileService reconcileService;

	@Autowired
	public FileExplorerService(WorkspaceManager workspaceManager,
			CatalogFileLocationRepository catalogFileLocationRepository, ScanExclusionService scanExclusionService,
			FileExplorerReconcileService reconcileService) {
		this.workspaceManager = workspaceManager;
		this.catalogFileLocationRepository = catalogFileLocationRepository;
		this.scanExclusionService = scanExclusionService;
		this.reconcileService = reconcileService;
	}

	public List<String> availableDrives() {
		File[] roots = File.listRoots();

		if (roots == null || roots.length == 0) {
			return List.of();
		}

		return Arrays.stream(roots).map(root -> PathUtils.normalize(root.toPath())).toList();
	}

	public FileExplorerView browse(String requestedPath, String requestedViewMode) {
		return browse(requestedPath, requestedViewMode, 0, 50);
	}

	public FileExplorerView browse(String requestedPath, String requestedViewMode, Integer requestedPage,
			Integer requestedSize) {
		return browse(requestedPath, requestedViewMode, requestedPage, requestedSize, "name");
	}

	public FileExplorerView browse(String requestedPath, String requestedViewMode, Integer requestedPage,
			Integer requestedSize, String requestedSort) {
		Path path = pathOrDefault(requestedPath);

		String viewMode = viewMode(requestedViewMode);

		int page = safePage(requestedPage);
		int size = safeSize(requestedSize);

		String parentPath = parentPath(path);

		if (!Files.exists(path)) {
			BrowsedFolder folder = new BrowsedFolder(path, parentPath, viewMode, false, false, false, 0, 0, 0, 0);

			return view(folder, page, size, List.of());
		}

		if (!Files.isDirectory(path)) {
			Path parent = path.getParent();

			String normalizedPath = PathUtils.normalize(path);

			Map<String, CatalogedFile> registeredFiles = new HashMap<>();

			if (parent != null) {
				catalogFileLocationRepository.findActiveByCurrentFolder(PathUtils.normalize(parent))
						.forEach(location -> registeredFiles.put(PathUtils.normalize(location.getCurrentPath()),
								catalogedFile(location)));
			}

			boolean registered = registeredFiles.containsKey(normalizedPath);

			BrowsedFolder folder = new BrowsedFolder(path, parentPath, viewMode, true, false, false, 0, 1, 0,
					registered ? 1 : 0);

			return view(folder, page, size, List.of(fileEntry(path, false, registeredFiles)));
		}

		FolderDatabaseState databaseState = databaseState(path);

		DiskListing diskListing = diskEntries(path, databaseState.registeredFiles());

		List<FileExplorerEntry> entries = new ArrayList<>(diskListing.entries());

		int diskFolders = (int) entries.stream().filter(FileExplorerEntry::directory).count();
		int diskFiles = (int) entries.stream().filter(entry -> !entry.directory()).count();

		List<FileExplorerEntry> missingEntries = databaseState.missingEntries();

		entries.addAll(missingEntries);

		Comparator<FileExplorerEntry> comparator = Comparator.comparing(FileExplorerEntry::directory).reversed();

		if ("date-oldest".equalsIgnoreCase(requestedSort) || "date-newest".equalsIgnoreCase(requestedSort)) {
			Comparator<LocalDateTime> dateComparator = "date-oldest".equalsIgnoreCase(requestedSort)
					? Comparator.naturalOrder()
					: Comparator.reverseOrder();

			comparator = comparator.thenComparing(FileExplorerEntry::modifiedAt, Comparator.nullsLast(dateComparator));
		} else {
			Comparator<FileExplorerEntry> nameComparator = Comparator
					.comparing(entry -> entry.name().toLowerCase(Locale.ROOT));

			if ("name-desc".equalsIgnoreCase(requestedSort)) {
				nameComparator = nameComparator.reversed();
			}

			comparator = comparator.thenComparing(nameComparator);
		}

		comparator = comparator.thenComparing(FileExplorerEntry::missing);

		entries.sort(comparator);

		BrowsedFolder folder = new BrowsedFolder(path, parentPath, viewMode, true, true, diskListing.accessDenied(),
				diskFolders, diskFiles, missingEntries.size(), databaseState.registeredFiles().size());

		return view(folder, page, size, entries);
	}

	private Path pathOrDefault(String requestedPath) {
		if (requestedPath == null || requestedPath.isBlank()) {
			return workspaceManager.temp();
		}

		return PathUtils.normalizePath(requestedPath);
	}

	private String viewMode(String requestedViewMode) {
		if (requestedViewMode == null || !VIEW_MODES.contains(requestedViewMode)) {
			return "details";
		}

		return requestedViewMode;
	}

	private int safePage(Integer page) {
		return page == null || page < 0 ? 0 : page;
	}

	private int safeSize(Integer size) {
		return size == null || !PAGE_SIZES.contains(size) ? 50 : size;
	}

	private FileExplorerView view(BrowsedFolder folder, int page, int size, List<FileExplorerEntry> entries) {
		int totalItems = entries.size();
		int totalPages = totalItems == 0 ? 1 : (int) Math.ceil((double) totalItems / size);
		int safePage = Math.min(page, totalPages - 1);
		int fromIndex = Math.min(safePage * size, totalItems);
		int toIndex = Math.min(fromIndex + size, totalItems);

		return new FileExplorerView(PathUtils.normalize(folder.path()), folder.parentPath(), folder.viewMode(),
				folder.exists(), folder.directory(), folder.accessDenied(), folder.folderCount(), folder.fileCount(),
				folder.missingCount(), folder.inventoriedCount(), safePage, size, totalItems, totalPages, safePage > 0,
				safePage + 1 < totalPages, entries.subList(fromIndex, toIndex));
	}

	private String parentPath(Path path) {
		Path parent = path.getParent();

		return parent == null ? null : PathUtils.normalize(parent);
	}

	private DiskListing diskEntries(Path folder, Map<String, CatalogedFile> registeredFiles) {
		try (Stream<Path> paths = Files.list(folder)) {
			List<FileExplorerEntry> entries = paths.filter(path -> !isExcluded(folder, path))
					.map(path -> fileEntry(path, true, registeredFiles)).toList();

			return new DiskListing(entries, false);
		} catch (IOException exception) {
			log.warn("Could not list folder: {}", folder, exception);

			return new DiskListing(List.of(), true);
		}
	}

	private FileExplorerEntry fileEntry(Path path, boolean includeDirectories,
			Map<String, CatalogedFile> registeredFiles) {
		boolean directory = includeDirectories && Files.isDirectory(path);

		Long size = null;

		LocalDateTime modifiedAt = null;

		try {
			if (!directory) {
				size = Files.size(path);
			}

			modifiedAt = LocalDateTime.ofInstant(Files.getLastModifiedTime(path).toInstant(), ZoneId.systemDefault());
		} catch (IOException _) {
			size = null;
		}

		String normalizedPath = PathUtils.normalize(path);

		CatalogedFile cataloged = directory ? null : registeredFiles.get(normalizedPath);

		String fileType;
		if (directory) {
			fileType = "FOLDER";
		} else if (cataloged != null && cataloged.fileType() != null) {
			fileType = cataloged.fileType().name();
		} else {
			fileType = extension(path);
		}

		Kind previewKind = directory ? Kind.NONE : FilePreviewSupport.kind(fileType);

		boolean image = previewKind == Kind.IMAGE;
		boolean video = previewKind == Kind.VIDEO;
		boolean pdf = previewKind == Kind.PDF;
		boolean text = previewKind == Kind.TEXT;
		boolean audio = previewKind == Kind.AUDIO;

		String previewUrl = image || video || pdf || text || audio ? previewUrl(path) : null;

		UUID mediaPublicId = cataloged == null ? null : cataloged.publicId();

		boolean registered = mediaPublicId != null;

		return new FileExplorerEntry(fileName(path), normalizedPath, directory, false, registered, fileType, image,
				video, pdf, text, audio, previewUrl, size, modifiedAt, DateTimeFormatUtils.human(modifiedAt), null,
				mediaPublicId);
	}

	/**
	 * Splits the database records for this folder into paths that are already
	 * inventoried and present on disk (used to flag matching disk entries as
	 * registered) and entries that are registered but no longer exist on disk
	 * (shown as "missing"). Missing records are also reconciled: they are marked as
	 * MISSING in the database (lifecycle_status), so they render once with the
	 * missing indicator and disappear on the next refresh. This is the only
	 * reconciliation that reaches records outside the monitored library, which the
	 * watcher never sees.
	 */
	private FolderDatabaseState databaseState(Path folder) {
		String currentFolder = PathUtils.normalize(folder);

		List<FileExplorerLocationProjection> locations = catalogFileLocationRepository
				.findActiveByCurrentFolder(currentFolder);

		Map<String, CatalogedFile> registeredFiles = new HashMap<>();

		Set<String> added = new HashSet<>();

		List<FileExplorerEntry> missingEntries = new ArrayList<>();

		Set<Long> missingIds = new HashSet<>();

		for (FileExplorerLocationProjection location : locations) {
			Path currentPath = PathUtils.normalizePath(location.getCurrentPath());

			String normalizedPath = PathUtils.normalize(currentPath);

			if (isExcluded(folder, currentPath)) {
				continue;
			}

			if (Files.exists(currentPath)) {
				registeredFiles.put(normalizedPath, catalogedFile(location));
			} else {
				if (location.getCatalogFileId() != null) {
					missingIds.add(location.getCatalogFileId());
				}

				if (added.add(normalizedPath)) {
					missingEntries.add(new FileExplorerEntry(fileName(currentPath), normalizedPath, false, true, true,
							location.getFileType().name(), false, false, false, false, false, null,
							location.getSizeBytes(), null, DateTimeFormatUtils.human(null), location.getCatalogFileId(),
							publicId(location)));
				}
			}
		}

		reconcileService.markMissing(List.copyOf(missingIds));

		return new FolderDatabaseState(registeredFiles, missingEntries);
	}

	private CatalogedFile catalogedFile(FileExplorerLocationProjection location) {
		return new CatalogedFile(publicId(location), location.getFileType());
	}

	private UUID publicId(FileExplorerLocationProjection location) {
		return UuidV7.orLegacy(location.getPublicId(), location.getCatalogFileId());
	}

	private String previewUrl(Path path) {
		return "/app/files/preview?path=" + UriUtils.encodeQueryParam(PathUtils.normalize(path), "UTF-8");
	}

	private boolean isExcluded(Path root, Path path) {
		return scanExclusionService.isExcluded(root, path);
	}

	private String fileName(Path path) {
		Path fileName = path.getFileName();

		return fileName == null ? path.toString() : fileName.toString();
	}

	private String extension(Path path) {
		String fileName = fileName(path);

		int index = fileName.lastIndexOf('.');

		if (index < 0 || index == fileName.length() - 1) {
			return "FILE";
		}

		return fileName.substring(index + 1).toUpperCase(Locale.ROOT);
	}
}