package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.Scan;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.MediaLocationReconcileProjection;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PhysicalFilePolicy;
import lombok.extern.slf4j.Slf4j;

/**
 * Diagnostic half of reconciliation: compares what's on disk against what's
 * registered in the database (missing on disk, missing in database,
 * file_key/current_path mismatches). The auto-repair half (pairing
 * disappearances with new files to detect renames) lives in the separate
 * {@link OrganizationRenameDetectionService}.
 */
@Slf4j
@Service
public class OrganizationReconcileService {

	private static final int PAGE_SIZE = 1_000;

	private final CatalogFileLocationRepository catalogFileLocationRepository;
	private final ScanExclusionService scanExclusionService;
	private final OperationLockService operationLockService;
	private final ReconcileApplier reconcileApplier;

	@Autowired
	public OrganizationReconcileService(CatalogFileLocationRepository catalogFileLocationRepository,
			ScanExclusionService scanExclusionService, OperationLockService operationLockService,
			ReconcileApplier reconcileApplier) {
		this.catalogFileLocationRepository = catalogFileLocationRepository;
		this.scanExclusionService = scanExclusionService;
		this.operationLockService = operationLockService;
		this.reconcileApplier = reconcileApplier;
	}

	public OrganizationReconcileResponse reconcile(OrganizationReconcileRequest request) {
		return scan(request).response();
	}

	/**
	 * Deliberately NOT {@code @Transactional}: the disk walk in {@link #scan}
	 * (minutes of pure I/O over a whole-drive tree of 100k+ files) must never hold
	 * a database transaction open, and an app restart mid-walk must not roll back a
	 * transaction that never had a chance to commit. The scan runs transaction-free
	 * (its paged repository reads each open their own short read transaction) and
	 * only the writes go through {@link ReconcileApplier#apply} in a short
	 * transaction of their own.
	 */
	public OrganizationReconcileResponse reconcileAndApply(OrganizationReconcileRequest request) {
		// Hold the tree lock for the whole apply so catalog mutations never race an
		// organization/undo/dedup on the same paths. The watcher's blocked gate already
		// skips this case; the lock closes the check-then-act window it cannot cover.
		try (var _ = operationLockService.acquire(ExecutionType.ORGANIZATION, request.source())) {
			Scan scan = scan(request);

			return reconcileApplier.apply(scan);
		} catch (OperationLockException _) {
			// Never reconcile against a moving target: end here without touching the
			// catalog
			// and let the next watcher pass retry - deferred, not dropped.
			log.info("Reconcile deferred on {}: another critical operation is in progress; it will retry next pass.",
					request.source());

			return OrganizationReconcileResponse.deferred(PathUtils.normalize(request.source()));
		}
	}

	private Scan scan(OrganizationReconcileRequest request) {
		Path source = request.source();

		validateSourcePath(source);

		Set<String> diskPaths = scanDisk(source, request.recursiveValue(), request.includeHiddenValue());

		ReconcileAccumulator accumulator = new ReconcileAccumulator(request.safeSampleLimit());

		readDatabasePaths(source, accumulator, diskPaths);

		for (String diskPath : diskPaths) {
			if (!accumulator.dbPaths().contains(diskPath)) {
				accumulator.addMissingInDatabase(diskPath);
			}
		}

		OrganizationReconcileResponse response = new OrganizationReconcileResponse(PathUtils.normalize(source),
				request.recursiveValue(), request.includeHiddenValue(), diskPaths.size(), accumulator.filesInDatabase(),
				accumulator.missingOnDisk(), accumulator.missingInDatabase(), accumulator.pathMismatches(),
				accumulator.missingOnDiskSamples(), accumulator.missingInDatabaseSamples(),
				accumulator.pathMismatchSamples(), 0, 0, 0);

		return new Scan(response, accumulator.pathSyncs());
	}

	private void readDatabasePaths(Path source, ReconcileAccumulator accumulator, Set<String> diskPaths) {
		String sourcePath = PathUtils.normalize(source);

		String descendantPattern = PathUtils.descendantLikePattern(sourcePath, source.getFileSystem().getSeparator());

		long afterId = 0;

		while (true) {
			List<MediaLocationReconcileProjection> rows = catalogFileLocationRepository.findForReconcile(sourcePath,
					descendantPattern, afterId, Limit.of(PAGE_SIZE));

			if (rows.isEmpty()) {
				return;
			}

			for (MediaLocationReconcileProjection row : rows) {
				processReconcileRow(source, accumulator, diskPaths, row);
			}

			afterId = rows.getLast().getCatalogFileId();
		}
	}

	private void processReconcileRow(Path source, ReconcileAccumulator accumulator, Set<String> diskPaths,
			MediaLocationReconcileProjection row) {
		String currentPath = PathUtils.normalize(row.getCurrentPath());

		String fileKey = PathUtils.normalize(row.getFileKey());

		if (isExcluded(source, PathUtils.normalizePath(currentPath))) {
			return;
		}

		accumulator.addDatabasePath(currentPath);

		if (!diskPaths.contains(currentPath)) {
			accumulator.addMissingOnDisk(row.getCatalogFileId(), currentPath);
		}

		if (!fileKey.equals(currentPath)) {
			accumulator.addPathMismatch(row.getCatalogFileId(), fileKey, currentPath);

			// Auto-repairable straggler: the catalog's file_key already points to a real
			// file on disk while the stale current_path does not (a move updated file_key
			// but current_path lagged). Queue a sync so current_path catches up.
			if (diskPaths.contains(fileKey) && !diskPaths.contains(currentPath)) {
				accumulator.addPathSync(row.getCatalogFileId(), fileKey);
			}
		}
	}

	private Set<String> scanDisk(Path source, boolean recursive, boolean includeHidden) {
		if (recursive) {
			return walkDisk(source, includeHidden);
		}

		return listDisk(source, includeHidden);
	}

	private Set<String> listDisk(Path source, boolean includeHidden) {
		try (Stream<Path> stream = Files.list(source)) {
			Set<String> paths = new HashSet<>();

			stream.filter(Files::isRegularFile).filter(path -> includeHidden || !isHidden(path))
					.filter(path -> !isExcluded(source, path)).map(PathUtils::normalize).forEach(paths::add);

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
	private Set<String> walkDisk(Path source, boolean includeHidden) {
		Set<String> paths = new HashSet<>();

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
			Set<String> paths) {
		if (attrs.isRegularFile() && PhysicalFilePolicy.isProcessable(file) && (includeHidden || !isHidden(file))
				&& !isExcluded(source, file)) {
			paths.add(PathUtils.normalize(file));
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