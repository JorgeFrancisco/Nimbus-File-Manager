package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.CatalogTimestamp;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.ScannedFile;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.Scan;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.projection.MediaLocationReconcileProjection;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.CoverageGenerated;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PhysicalFilePolicy;
import lombok.extern.slf4j.Slf4j;

/**
 * Diagnostic half of reconciliation: compares what is on disk against what the
 * catalog holds - files it cannot find, files nobody catalogued, and files whose
 * cheap facts moved under it. It writes nothing. Recognising a file by its
 * content and converging the catalog on what was found are the other halves,
 * and they live in {@code RelocationByContent} and {@code ReconcileConvergence}.
 */
@Slf4j
@Service
public class OrganizationReconcileService {

	private static final int PAGE_SIZE = 1_000;

	private final CatalogFileLocationRepository catalogFileLocationRepository;
	private final ScanExclusionService scanExclusionService;

	@Autowired
	public OrganizationReconcileService(CatalogFileLocationRepository catalogFileLocationRepository,
			ScanExclusionService scanExclusionService) {
		this.catalogFileLocationRepository = catalogFileLocationRepository;
		this.scanExclusionService = scanExclusionService;
	}

	public OrganizationReconcileResponse reconcile(OrganizationReconcileRequest request) {
		return scan(request).response();
	}

	/**
	 * What a reconcile would find, without writing any of it.
	 *
	 * <p>
	 * Package-private rather than public: the screen reaches it through
	 * {@link #reconcile}, and the applying pass is the only other caller - it lives
	 * in this package precisely because holding the applier anywhere the
	 * application can reach was the problem.
	 */
	Scan scan(OrganizationReconcileRequest request) {
		Path source = request.source();

		validateSourcePath(source);

		Map<String, ScannedFile> disk = scanDisk(source, request.recursiveValue(), request.includeHiddenValue());

		ReconcileAccumulator accumulator = new ReconcileAccumulator(request.safeSampleLimit());

		readDatabasePaths(source, request.recursiveValue(), accumulator, disk);

		for (String diskPath : disk.keySet()) {
			if (!accumulator.dbPaths().contains(diskPath)) {
				accumulator.addMissingInDatabase(diskPath);
			}
		}

		OrganizationReconcileResponse response = new OrganizationReconcileResponse(PathUtils.normalize(source),
				request.recursiveValue(), request.includeHiddenValue(), disk.size(), accumulator.filesInDatabase(),
				accumulator.missingOnDisk(), accumulator.missingInDatabase(), accumulator.missingOnDiskSamples(),
				accumulator.missingInDatabaseSamples(), 0, 0, 0, 0);

		return new Scan(response, accumulator.missingFiles(), accumulator.physicalOnly(),
				accumulator.contentSuspects());
	}

	/**
	 * The catalog side of the comparison, read in the same scope the disk was
	 * scanned in.
	 *
	 * <p>
	 * The symmetry is the whole point, and it was missing: the walk honoured
	 * {@code recursive} while this read always asked for the folder and every
	 * descendant. A shallow pass therefore compared one folder's worth of disk
	 * against a whole subtree's worth of catalog, concluded that everything in
	 * every subfolder was gone, and marked it missing - reachable from the
	 * {@code watch-recursive} setting alone, and silent, because a reconcile that
	 * repairs is doing exactly what it is supposed to be doing.
	 */
	private void readDatabasePaths(Path source, boolean recursive, ReconcileAccumulator accumulator,
			Map<String, ScannedFile> disk) {
		String sourcePath = PathUtils.normalize(source);

		String descendantPattern = PathUtils.descendantLikePattern(sourcePath, source.getFileSystem().getSeparator());

		long afterId = 0;

		while (true) {
			List<MediaLocationReconcileProjection> rows = recursive
					? catalogFileLocationRepository.findForReconcile(sourcePath, descendantPattern, afterId,
							Limit.of(PAGE_SIZE))
					: catalogFileLocationRepository.findForShallowReconcile(sourcePath, afterId, Limit.of(PAGE_SIZE));

			if (rows.isEmpty()) {
				return;
			}

			for (MediaLocationReconcileProjection row : rows) {
				processReconcileRow(source, accumulator, disk, row);
			}

			afterId = rows.getLast().getCatalogFileId();
		}
	}

	private void processReconcileRow(Path source, ReconcileAccumulator accumulator, Map<String, ScannedFile> disk,
			MediaLocationReconcileProjection row) {
		String currentPath = PathUtils.normalize(row.getCurrentPath());

		if (isExcluded(source, PathUtils.normalizePath(currentPath))) {
			return;
		}

		accumulator.addDatabasePath(currentPath);

		ScannedFile observed = disk.get(currentPath);

		if (observed == null) {
			accumulator.addMissingOnDisk(row.getCatalogFileId(), currentPath);

			return;
		}

		// Still there, but is it still the same file? The walk was handed its size and
		// its timestamp, so asking costs nothing - and neither proves anything, which
		// is why a divergence asks for a reading rather than concluding one.
		if (!Objects.equals(row.getSizeBytes(), observed.sizeBytes())
				|| !observed.modifiedAt().equals(row.getModifiedAt())) {
			accumulator.addContentSuspect(row.getCatalogFileId(), currentPath);
		}

		// A mismatch between file_key and current_path used to be detected and repaired
		// here. There is one column now, so the two cannot disagree - and a check that
		// compares a value with itself is worse than no check, because it reads like
		// one.
	}

	private Map<String, ScannedFile> scanDisk(Path source, boolean recursive, boolean includeHidden) {
		if (recursive) {
			return walkDisk(source, includeHidden);
		}

		return listDisk(source, includeHidden);
	}

	private Map<String, ScannedFile> listDisk(Path source, boolean includeHidden) {
		try (Stream<Path> stream = Files.list(source)) {
			Map<String, ScannedFile> paths = new HashMap<>();

			stream.filter(Files::isRegularFile).filter(path -> includeHidden || !isHidden(path))
					.filter(path -> !isExcluded(source, path))
					// A single level has no walk to be told anything by, so the attributes
					// are read here - one stat for a file the pass is about to consider.
					.forEach(path -> scannedOf(path).ifPresent(scanned -> paths.put(PathUtils.normalize(path),
							scanned)));

			return paths;
		} catch (IOException e) {
			throw new IllegalStateException("Could not scan path: " + source, e);
		}
	}

	/**
	 * Uses a resilient file visitor instead of
	 * {@link Files#walk(Path, java.nio.file.FileVisitOption...)} because a
	 * lazily-consumed walk stream throws an unchecked
	 * {@link java.io.UncheckedIOException} the moment it reaches an unreadable
	 * subfolder (for example a permission-protected system folder), aborting the
	 * whole reconcile. The visitor instead skips whatever it cannot read and keeps
	 * going.
	 */
	private Map<String, ScannedFile> walkDisk(Path source, boolean includeHidden) {
		Map<String, ScannedFile> paths = new HashMap<>();

		try {
			Files.walkFileTree(source, new SimpleFileVisitor<Path>() {

				@Override
				public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
					return directoryDecision(directory, source, includeHidden);
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					indexIfEligible(file, attrs, source, includeHidden, paths);

					return FileVisitResult.CONTINUE;
				}

				@Override
				@CoverageGenerated("Only the operating system denying an entry reaches this")
				public FileVisitResult visitFileFailed(Path file, IOException exception) {
					// Access-denied on system/protected folders is expected during a full-disk
					// scan and must not spam the log with stack traces on every reconcile cycle;
					// keep it at debug. Genuinely unexpected I/O failures stay visible as warnings.
					if (exception instanceof AccessDeniedException) {
						log.debug("Skipping inaccessible {} while reconciling {}", file, source);
					} else {
						log.warn("Could not access {} while reconciling {}", file, source, exception);
					}

					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			throw new IllegalStateException("Could not scan path: " + source, e);
		}

		return paths;
	}

	private FileVisitResult directoryDecision(Path directory, Path source, boolean includeHidden) {
		// Skip the quarantine subtree entirely: soft-deleted duplicates living there
		// must not be reconciled against the catalog.
		if (scanExclusionService.isWithinQuarantine(directory)) {
			return FileVisitResult.SKIP_SUBTREE;
		}

		// When hidden content is not indexed, do not even descend into hidden
		// directories: their files would be filtered out anyway, and system folders
		// like Windows' "System Volume Information" are hidden and unreadable, so
		// descending only wastes work and floods the log with access-denied warnings.
		// The source root itself is never skipped even if it happens to be hidden.
		if (!includeHidden && !directory.equals(source) && isHidden(directory)) {
			return FileVisitResult.SKIP_SUBTREE;
		}

		// Never descend into symlinked/junction directories - only physical trees.
		return PhysicalFilePolicy.isProcessable(directory) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
	}

	private void indexIfEligible(Path file, BasicFileAttributes attrs, Path source, boolean includeHidden,
			Map<String, ScannedFile> paths) {
		if (attrs.isRegularFile() && PhysicalFilePolicy.isProcessable(file) && (includeHidden || !isHidden(file))
				&& !isExcluded(source, file)) {
			// The attributes the visitor was given, kept rather than dropped: they are
			// what lets the pass notice that a file it found is not the file it
			// catalogued.
			paths.put(PathUtils.normalize(file),
					new ScannedFile(file, attrs.size(), CatalogTimestamp.observed(attrs.lastModifiedTime())));
		}
	}

	private Optional<ScannedFile> scannedOf(Path path) {
		try {
			BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);

			return Optional.of(
					new ScannedFile(path, attributes.size(), CatalogTimestamp.observed(attributes.lastModifiedTime())));
		} catch (IOException _) {
			// Unreadable entries are skipped, exactly as the recursive walk skips them.
			return Optional.empty();
		}
	}

	private boolean isHidden(Path path) {
		try {
			return Files.isHidden(path);
		} catch (IOException _) {
			return false;
		}
	}

	private boolean isExcluded(Path root, Path path) {
		return scanExclusionService.isExcluded(root, path);
	}

	private void validateSourcePath(Path source) {
		if (!Files.exists(source)) {
			throw new IllegalArgumentException("Path does not exist: " + source);
		}

		if (!Files.isDirectory(source)) {
			throw new IllegalArgumentException("Path is not a directory: " + source);
		}
	}
}