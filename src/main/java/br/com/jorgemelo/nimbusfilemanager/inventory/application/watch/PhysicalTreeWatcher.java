package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.FileChangeSource;
import br.com.jorgemelo.nimbusfilemanager.shared.application.CoverageGenerated;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PhysicalFilePolicy;
import lombok.extern.slf4j.Slf4j;

/**
 * Physical-only, resilient recursive directory watcher built directly on
 * {@link WatchService}.
 *
 * <p>
 * It replaces Spring Integration's {@code WatchServiceDirectoryScanner} for the
 * inventory monitor because that scanner's internal walk does not override
 * {@code visitFileFailed}: a single protected directory at a drive root
 * (Windows {@code System Volume Information} / {@code $RECYCLE.BIN}) makes the
 * default {@link SimpleFileVisitor} re-throw, which aborts the whole
 * registration and logs {@code Failed to walk directory}. Here,
 * {@code visitFileFailed} returns {@link FileVisitResult#CONTINUE}, so such
 * directories are skipped and the rest of the tree is still registered.
 *
 * <p>
 * Physical-only policy: symbolic links, junctions/reparse points and
 * {@code .lnk} shortcuts are never descended into nor reported, via
 * {@link PhysicalFilePolicy}.
 *
 * <p>
 * No Java {@link WatchService} is recursive (Windows included): every directory
 * of the tree must be registered individually, and newly created
 * sub-directories must be registered when their {@code ENTRY_CREATE} event
 * arrives. This class does both.
 *
 * <p>
 * Not internally synchronized: callers must serialize access (the inventory
 * monitor already does this on the same monitor as its reconfigure/poll cycle).
 */
@Slf4j
public class PhysicalTreeWatcher implements FileChangeSource {

	private final Path root;
	private final boolean recursive;
	private final WatchService watchService;
	private final Map<Path, WatchKey> keys = new ConcurrentHashMap<>();

	private volatile boolean overflow;

	public PhysicalTreeWatcher(Path root, boolean recursive) throws IOException {
		this.root = root;
		this.recursive = recursive;
		this.watchService = root.getFileSystem().newWatchService();

		registerTree(root);
	}

	/**
	 * Drains all pending events and returns the physical files that changed
	 * (created, modified or deleted). Newly created physical sub-directories are
	 * registered so their contents are watched too. An {@code OVERFLOW} event sets
	 * a flag consumable via {@link #consumeOverflow()} - the caller should trigger
	 * an early reconcile because events may have been dropped.
	 */
	@Override
	public List<Path> pollChangedFiles() {
		List<Path> changed = new ArrayList<>();

		WatchKey key;

		while ((key = watchService.poll()) != null) {
			Path directory = (Path) key.watchable();

			for (WatchEvent<?> event : key.pollEvents()) {
				handleEvent(directory, event, changed);
			}

			if (!key.reset()) {
				// The watched directory is gone or no longer accessible.
				keys.values().remove(key);
			}
		}

		return changed;
	}

	// Package-private for deterministic unit testing of the event branches
	// (create/modify/delete/overflow) without depending on real WatchService
	// timing.
	void handleEvent(Path directory, WatchEvent<?> event, List<Path> changed) {
		WatchEvent.Kind<?> kind = event.kind();

		if (StandardWatchEventKinds.OVERFLOW.equals(kind)) {
			overflow = true;

			return;
		}

		Path name = (Path) event.context();

		if (name == null) {
			return;
		}

		Path child = directory.resolve(name);

		if (StandardWatchEventKinds.ENTRY_DELETE.equals(kind)) {
			WatchKey removed = keys.remove(child);

			if (removed != null) {
				removed.cancel();
			}

			// The path is already gone, so it cannot be inspected; signal it as a
			// change so the debounced reconcile removes it from the catalog.
			changed.add(child);

			return;
		}

		// CREATE or MODIFY.
		if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
			if (recursive && PhysicalFilePolicy.isProcessable(child)) {
				registerTree(child);
			}

			return;
		}

		if (PhysicalFilePolicy.isProcessable(child)) {
			changed.add(child);
		}
	}

	/**
	 * Recursively registers every physical directory under {@code start}. Skips
	 * non-physical directories (symlink/junction) whole, and never aborts on an
	 * unreadable/protected directory - it is simply skipped.
	 */
	private void registerTree(Path start) {
		int maxDepth = recursive ? Integer.MAX_VALUE : 1;

		try {
			Files.walkFileTree(start, EnumSet.noneOf(FileVisitOption.class), maxDepth, new SimpleFileVisitor<Path>() {

				@Override
				public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
					if (!PhysicalFilePolicy.isProcessable(directory)) {
						return FileVisitResult.SKIP_SUBTREE;
					}

					register(directory);

					return FileVisitResult.CONTINUE;
				}

				@Override
				@CoverageGenerated("Only the operating system denying an entry reaches this")
				public FileVisitResult visitFileFailed(Path file, IOException exception) {
					// Unreadable/protected entry (e.g. System Volume Information):
					// skip it and keep registering the rest of the tree.
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException exception) {
			// visitFileFailed already swallows per-entry failures; this guards the
			// unlikely case of the root itself being unreadable.
			log.debug("Could not register subtree {} for watching", start, exception);
		}
	}

	private void register(Path directory) {
		if (keys.containsKey(directory)) {
			return;
		}

		try {
			WatchKey key = directory.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
					StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);

			keys.putIfAbsent(directory, key);
		} catch (IOException exception) {
			// A directory we cannot register (e.g. removed between walk and register)
			// is simply not watched; the periodic reconcile still covers it.
			log.debug("Could not register {} for watching", directory, exception);
		}
	}

	/** @return the monitored root. */
	@Override
	public Path root() {
		return root;
	}

	/** @return {@code true} if the given directory is currently being watched. */
	boolean isWatching(Path directory) {
		return keys.containsKey(directory);
	}

	/**
	 * @return how many directories are currently registered. Test/diagnostic aid.
	 */
	int watchedDirectoryCount() {
		return keys.size();
	}

	/**
	 * Returns whether an overflow happened since the last call and clears the flag.
	 */
	@Override
	public boolean consumeOverflow() {
		boolean happened = overflow;

		overflow = false;

		return happened;
	}

	@Override
	public void close() throws IOException {
		keys.clear();

		watchService.close();
	}
}