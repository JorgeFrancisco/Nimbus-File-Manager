package br.com.jorgemelo.nimbusfilemanager.inventory.application.scanner;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.CatalogTimestamp;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.ScanOptions;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.ScannedFile;
import br.com.jorgemelo.nimbusfilemanager.settings.application.FolderMatcher;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.shared.util.ExtensionUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PhysicalFilePolicy;

@Service
public class FileScanner {

	/**
	 * Used only to resolve the configured quarantine folder so its whole subtree is
	 * skipped: files soft-deleted into quarantine must never be re-inventoried
	 * (otherwise they reappear as duplicate candidates). Nullable so the no-arg
	 * constructor keeps working in unit tests that don't need it.
	 */
	private final ScanExclusionService scanExclusionService;

	@Autowired
	public FileScanner(ScanExclusionService scanExclusionService) {
		this.scanExclusionService = scanExclusionService;
	}

	public FileScanner() {
		this(null);
	}

	/**
	 * Fast pass that only counts files matching the same filters used by
	 * {@link #stream}, without processing metadata. Used to calculate a real
	 * percentage denominator before a long scan.
	 */
	public long count(Path sourcePath, ScanOptions options) {
		validateSourcePath(sourcePath);

		ScanOptions effectiveOptions = options != null ? options : ScanOptions.defaultOptions();

		try (Stream<ScannedFile> stream = createStream(sourcePath, effectiveOptions)) {
			return filteredFiles(stream, sourcePath, effectiveOptions).count();
		} catch (IOException e) {
			throw new IllegalStateException("Could not scan path: " + sourcePath, e);
		}
	}

	/**
	 * Same filtering pipeline as {@link #count}, but returned as a
	 * lazily-evaluated, unsorted {@link Stream} the caller pulls from instead of a
	 * pre-materialized list. Used by the Spring Batch inventory {@code ItemReader},
	 * which pulls one file at a time from this stream across multiple chunks. The
	 * caller owns the returned stream and must close it (it wraps a filesystem
	 * resource, same as {@link Files#walk}) - callers must not call {@link #count}
	 * concurrently against a stream still open from this method for the same
	 * instance, though in practice each call opens an independent underlying walk.
	 */
	public Stream<ScannedFile> stream(Path sourcePath, ScanOptions options) {
		validateSourcePath(sourcePath);

		ScanOptions effectiveOptions = options != null ? options : ScanOptions.defaultOptions();

		try {
			return filteredFiles(createStream(sourcePath, effectiveOptions), sourcePath, effectiveOptions);
		} catch (IOException e) {
			throw new IllegalStateException("Could not scan path: " + sourcePath, e);
		}
	}

	private Stream<ScannedFile> filteredFiles(Stream<ScannedFile> stream, Path sourcePath,
			ScanOptions options) {
		// PhysicalFilePolicy drops symbolic links and .lnk shortcuts so they are
		// never inventoried. Directory symlinks are never descended into because the
		// walk below never uses FileVisitOption.FOLLOW_LINKS.
		return stream.filter(scanned -> Files.isRegularFile(scanned.path()))
				.filter(scanned -> PhysicalFilePolicy.isProcessable(scanned.path()))
				.filter(scanned -> options.includeHidden() || !isHidden(scanned.path()))
				.filter(scanned -> !isWithinQuarantine(scanned.path()))
				.filter(scanned -> !matchesExcludedFolder(sourcePath, scanned.path(), options))
				.filter(scanned -> matchesExtension(scanned.path(), options));
	}

	private Stream<ScannedFile> createStream(Path sourcePath, ScanOptions options) throws IOException {
		if (!options.recursive()) {
			// A single level has no walk to be told anything by, so the attributes are
			// asked for here - one stat for a file this is about to consider anyway.
			return Files.list(sourcePath).map(FileScanner::scannedOf).filter(Objects::nonNull);
		}

		return walkPhysicalTree(sourcePath, options.includeHidden());
	}

	/**
	 * Recursive walk that (1) never follows symbolic links/junctions - it skips
	 * their whole subtree, so the app only ever sees physical paths and can't loop
	 * on a circular link; (2) prunes hidden/system directory subtrees (e.g. Windows
	 * {@code System Volume Information} or {@code $RECYCLE.BIN} at a drive root,
	 * both hidden) unless {@code includeHidden} is set, so their contents are never
	 * inventoried even when the files inside are not individually flagged hidden -
	 * the per-file hidden filter alone misses those; and (3) never aborts the whole
	 * scan on an unreadable or protected directory: such entries are skipped
	 * instead. A plain {@link Files#walk} would throw {@code UncheckedIOException}
	 * on the first such directory and lose the entire tree. This mirrors the
	 * directory pruning the reconcile walk already performs, so both stay in sync.
	 */
	private Stream<ScannedFile> walkPhysicalTree(Path sourcePath, boolean includeHidden) throws IOException {
		List<ScannedFile> files = new ArrayList<>();

		Files.walkFileTree(sourcePath, new SimpleFileVisitor<Path>() {

			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
				if (isWithinQuarantine(directory)) {
					return FileVisitResult.SKIP_SUBTREE;
				}

				if (!includeHidden && !directory.equals(sourcePath) && isHidden(directory)) {
					return FileVisitResult.SKIP_SUBTREE;
				}

				return PhysicalFilePolicy.isProcessable(directory) ? FileVisitResult.CONTINUE
						: FileVisitResult.SKIP_SUBTREE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				// The attributes travel with the path. The walk was given them to decide
				// whether to descend; throwing them away is what made a later pass unable
				// to tell a catalogued file from the file it catalogued without opening it.
				files.add(new ScannedFile(file, attrs.size(), CatalogTimestamp.observed(attrs.lastModifiedTime())));

				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exception) {
				// Unreadable/protected entry: skip it and keep scanning the rest.
				return FileVisitResult.CONTINUE;
			}
		});

		return files.stream();
	}

	private static ScannedFile scannedOf(Path path) {
		try {
			BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);

			return new ScannedFile(path, attributes.size(), CatalogTimestamp.observed(attributes.lastModifiedTime()));
		} catch (IOException _) {
			// Unreadable entries are skipped, exactly as the recursive walk skips them.
			return null;
		}
	}

	private boolean isWithinQuarantine(Path path) {
		return scanExclusionService != null && scanExclusionService.isWithinQuarantine(path);
	}

	private boolean matchesExtension(Path path, ScanOptions options) {
		String extension = ExtensionUtils.fromPath(path);

		List<String> include = normalize(options.includeExtensions());
		List<String> exclude = normalize(options.excludeExtensions());

		if (!include.isEmpty() && !include.contains(extension)) {
			return false;
		}

		return !exclude.contains(extension);
	}

	private boolean matchesExcludedFolder(Path sourcePath, Path path, ScanOptions options) {
		List<String> excludeFolders = normalizePatterns(options.excludeFolders());

		if (excludeFolders.isEmpty()) {
			return false;
		}

		Path parent = path.getParent();

		if (parent == null) {
			return false;
		}

		List<FolderMatcher> matchers = excludeFolders.stream().map(FolderMatcher::of).toList();

		Path scoped = sourcePath != null && parent.startsWith(sourcePath) ? sourcePath.relativize(parent) : parent;

		for (Path part : scoped) {
			String folderName = part.toString();

			if (matchers.stream().anyMatch(matcher -> matcher.matches(folderName))) {
				return true;
			}
		}

		return false;
	}

	private List<String> normalize(List<String> extensions) {
		if (extensions == null) {
			return List.of();
		}

		return extensions.stream().filter(value -> value != null && !value.isBlank()).map(ExtensionUtils::normalize)
				.toList();
	}

	private List<String> normalizePatterns(List<String> patterns) {
		if (patterns == null) {
			return List.of();
		}

		return patterns.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).distinct()
				.toList();
	}

	private boolean isHidden(Path path) {
		try {
			return Files.isHidden(path);
		} catch (IOException _) {
			return false;
		}
	}

	private void validateSourcePath(Path sourcePath) {
		if (sourcePath == null) {
			throw new IllegalArgumentException("Source path must not be null.");
		}

		if (!Files.exists(sourcePath)) {
			throw new IllegalArgumentException("Path does not exist: " + sourcePath);
		}

		if (!Files.isDirectory(sourcePath)) {
			throw new IllegalArgumentException("Path is not a directory: " + sourcePath);
		}
	}
}