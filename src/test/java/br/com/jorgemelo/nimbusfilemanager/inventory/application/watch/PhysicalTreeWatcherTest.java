package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the resilient, physical-only WatchService registration used to monitor
 * folders (including drive roots such as {@code D:\}). Most branches are
 * exercised deterministically through {@link PhysicalTreeWatcher#handleEvent}
 * so they do not depend on real filesystem-watch timing; one end-to-end test
 * uses the live {@link java.nio.file.WatchService} for a file created in a
 * sub-directory.
 */
class PhysicalTreeWatcherTest {

	@TempDir
	Path tempDir;

	@Test
	void registersRootAndCommonSubdirectoriesRecursively() throws Exception {
		Path fotos = Files.createDirectory(tempDir.resolve("fotos"));
		Path nested = Files.createDirectory(fotos.resolve("2026"));

		try (PhysicalTreeWatcher watcher = new PhysicalTreeWatcher(tempDir, true)) {
			assertThat(watcher.isWatching(tempDir)).isTrue();
			assertThat(watcher.isWatching(fotos)).isTrue();
			assertThat(watcher.isWatching(nested)).isTrue();
			assertThat(watcher.watchedDirectoryCount()).isEqualTo(3);
		}
	}

	@Test
	void nonRecursiveRegistersOnlyTheRoot() throws Exception {
		Path fotos = Files.createDirectory(tempDir.resolve("fotos"));

		try (PhysicalTreeWatcher watcher = new PhysicalTreeWatcher(tempDir, false)) {
			assertThat(watcher.isWatching(tempDir)).isTrue();
			assertThat(watcher.isWatching(fotos)).isFalse();
			assertThat(watcher.watchedDirectoryCount()).isEqualTo(1);
		}
	}

	@Test
	void unreadableDirectoryIsSkippedWithoutAbortingRegistration() throws Exception {
		Path readable = Files.createDirectory(tempDir.resolve("ok"));
		Path locked = Files.createDirectory(tempDir.resolve("protected"));

		try {
			Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
		} catch (UnsupportedOperationException | IOException exception) {
			Assumptions.abort("POSIX permissions not supported (e.g. Windows): " + exception.getMessage());
		}

		try (PhysicalTreeWatcher watcher = new PhysicalTreeWatcher(tempDir, true)) {
			// A protected directory (mimicking Windows "System Volume Information") must
			// not abort the whole registration: the readable siblings are still watched.
			assertThat(watcher.isWatching(tempDir)).isTrue();
			assertThat(watcher.isWatching(readable)).isTrue();

			// When genuinely unreadable (non-root run), the protected dir is skipped.
			if (!Files.isReadable(locked)) {
				assertThat(watcher.isWatching(locked)).isFalse();
			}
		} finally {
			Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
		}
	}

	@Test
	void symbolicLinkedDirectoryIsNotDescendedInto() throws Exception {
		Path realDir = Files.createDirectory(tempDir.resolve("real"));
		Path link = tempDir.resolve("link");
		assumeSymlink(link, realDir);

		try (PhysicalTreeWatcher watcher = new PhysicalTreeWatcher(tempDir, true)) {
			assertThat(watcher.isWatching(tempDir)).isTrue();
			assertThat(watcher.isWatching(realDir)).isTrue();
			assertThat(watcher.isWatching(link)).isFalse();
		}
	}

	@Test
	void createOrModifyEventForPhysicalFileIsReportedAsChange() throws Exception {
		Path file = Files.writeString(tempDir.resolve("photo.jpg"), "jpg");

		try (PhysicalTreeWatcher watcher = new PhysicalTreeWatcher(tempDir, true)) {
			List<Path> changed = new ArrayList<>();

			watcher.handleEvent(tempDir, event(StandardWatchEventKinds.ENTRY_MODIFY, Path.of("photo.jpg")), changed);

			assertThat(changed).containsExactly(file);
		}
	}

	@Test
	void shortcutEventIsNotReportedAsChange() throws Exception {
		Path shortcut = Files.writeString(tempDir.resolve("target.lnk"), "lnk");

		try (PhysicalTreeWatcher watcher = new PhysicalTreeWatcher(tempDir, true)) {
			List<Path> changed = new ArrayList<>();

			watcher.handleEvent(tempDir, event(StandardWatchEventKinds.ENTRY_CREATE, Path.of("target.lnk")), changed);

			assertThat(changed).doesNotContain(shortcut);
		}
	}

	@Test
	void deleteEventIsReportedSoTheReconcileCanRemoveIt() throws Exception {
		try (PhysicalTreeWatcher watcher = new PhysicalTreeWatcher(tempDir, true)) {
			List<Path> changed = new ArrayList<>();

			watcher.handleEvent(tempDir, event(StandardWatchEventKinds.ENTRY_DELETE, Path.of("gone.jpg")), changed);

			assertThat(changed).containsExactly(tempDir.resolve("gone.jpg"));
		}
	}

	/**
	 * A folder created inside the watched tree has to start being watched at once,
	 * or everything dropped into it afterwards is invisible - which is how a whole
	 * new album could arrive without a single event.
	 */
	@Test
	void directoryCreatedInsideTheTreeStartsBeingWatched() throws Exception {
		try (PhysicalTreeWatcher watcher = new PhysicalTreeWatcher(tempDir, true)) {
			Path album = Files.createDirectory(tempDir.resolve("album"));
			Path inside = Files.createDirectory(album.resolve("2026"));

			List<Path> changed = new ArrayList<>();

			watcher.handleEvent(tempDir, event(StandardWatchEventKinds.ENTRY_CREATE, Path.of("album")), changed);

			assertThat(watcher.isWatching(album)).isTrue();
			assertThat(watcher.isWatching(inside)).isTrue();

			// A new folder is not itself a changed file; what lands in it will be.
			assertThat(changed).isEmpty();
		}
	}

	/** A folder that leaves the tree stops being watched, never leaking a key. */
	@Test
	void deletedDirectoryStopsBeingWatched() throws Exception {
		Path album = Files.createDirectory(tempDir.resolve("album"));

		try (PhysicalTreeWatcher watcher = new PhysicalTreeWatcher(tempDir, true)) {
			assertThat(watcher.isWatching(album)).isTrue();

			Files.delete(album);

			List<Path> changed = new ArrayList<>();

			watcher.handleEvent(tempDir, event(StandardWatchEventKinds.ENTRY_DELETE, Path.of("album")), changed);

			assertThat(watcher.isWatching(album)).isFalse();
			assertThat(watcher.watchedDirectoryCount()).isEqualTo(1);
		}
	}

	/** An event the platform hands over without a name has nothing to report. */
	@Test
	void eventWithoutANameIsIgnored() throws Exception {
		try (PhysicalTreeWatcher watcher = new PhysicalTreeWatcher(tempDir, true)) {
			List<Path> changed = new ArrayList<>();

			watcher.handleEvent(tempDir, event(StandardWatchEventKinds.ENTRY_CREATE, null), changed);

			assertThat(changed).isEmpty();
		}
	}

	@Test
	void overflowEventSetsAConsumableFlagWithoutReportingFiles() throws Exception {
		try (PhysicalTreeWatcher watcher = new PhysicalTreeWatcher(tempDir, true)) {
			List<Path> changed = new ArrayList<>();

			watcher.handleEvent(tempDir, event(StandardWatchEventKinds.OVERFLOW, null), changed);

			assertThat(changed).isEmpty();
			assertThat(watcher.consumeOverflow()).isTrue();
			// The flag is cleared after being consumed.
			assertThat(watcher.consumeOverflow()).isFalse();
		}
	}

	@Test
	void fileCreatedInSubdirectoryGeneratesAChangeEvent() throws Exception {
		Path fotos = Files.createDirectory(tempDir.resolve("fotos"));

		try (PhysicalTreeWatcher watcher = new PhysicalTreeWatcher(tempDir, true)) {
			Files.writeString(fotos.resolve("new.jpg"), "jpg");

			Path detected = awaitChange(watcher, "new.jpg");

			assertThat(detected).isNotNull();
		}
	}

	private Path awaitChange(PhysicalTreeWatcher watcher, String fileName) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 15_000;

		while (System.currentTimeMillis() < deadline) {
			for (Path changed : watcher.pollChangedFiles()) {
				if (changed.getFileName().toString().equals(fileName)) {
					return changed;
				}
			}

			Thread.sleep(200);
		}

		return null;
	}

	private WatchEvent<?> event(WatchEvent.Kind<?> kind, Path context) {
		return new WatchEvent<Path>() {

			@Override
			public Kind<Path> kind() {
				@SuppressWarnings("unchecked")
				Kind<Path> typed = (Kind<Path>) kind;

				return typed;
			}

			@Override
			public int count() {
				return 1;
			}

			@Override
			public Path context() {
				return context;
			}
		};
	}

	private void assumeSymlink(Path link, Path target) {
		try {
			Files.createSymbolicLink(link, target);
		} catch (IOException | UnsupportedOperationException exception) {
			Assumptions.abort("Symbolic links not supported in this environment: " + exception.getMessage());
		}
	}
}