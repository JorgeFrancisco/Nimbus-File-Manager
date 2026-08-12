package br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.rdcw.windows;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.FileSystemChange;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.FileChangeSource;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw.RdcwUnavailableException;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn.UsnCursorStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end validation of the real {@code ReadDirectoryChangesW} source
 * against the actual file system - <b>without elevation</b>, which is the whole
 * point: one directory handle on the root, recursive detection, and no folder
 * lock. Windows-only ({@link EnabledOnOs}); self-skips only if even the
 * directory handle cannot be opened. The USN catch-up is skipped silently here
 * (no privilege), so this exercises the real-time path alone.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsRdcwIntegrationTest {

	private static final int BUFFER = 262_144;
	private static final int MAX_POLLS = 60;
	private static final long POLL_PAUSE_MILLIS = 50;

	private final UsnCursorStore cursorStore = mock(UsnCursorStore.class);

	private FileChangeSource open(Path root) {
		when(cursorStore.load(root.toAbsolutePath().normalize().toString())).thenReturn(Optional.empty());

		try {
			return WindowsChangeSourceSupport.open(root, cursorStore, BUFFER);
		} catch (RdcwUnavailableException unavailable) {
			Assumptions.abort("ReadDirectoryChangesW not available: " + unavailable.getMessage());

			throw unavailable;
		}
	}

	@Test
	void detectsCreateModifyRenameMoveAndDeleteInDeepSubfolders(@TempDir Path root)
			throws IOException, InterruptedException {
		FileChangeSource source = open(root);

		try {
			Path deep = root.resolve("2024").resolve("05").resolve("day");
			Files.createDirectories(deep);

			Path created = deep.resolve("photo.jpg");
			Files.writeString(created, "first");
			Assertions.assertThat(awaitChange(source, created)).as("create").isTrue();

			Files.writeString(created, "second-longer-content");
			Assertions.assertThat(awaitChange(source, created)).as("modify").isTrue();

			Path renamed = deep.resolve("renamed.jpg");
			Files.move(created, renamed);
			Assertions.assertThat(awaitChange(source, renamed)).as("rename").isTrue();

			Path otherDir = root.resolve("2024").resolve("06");
			Files.createDirectories(otherDir);
			Path moved = otherDir.resolve("renamed.jpg");
			Files.move(renamed, moved);
			Assertions.assertThat(awaitChange(source, moved)).as("move").isTrue();

			Files.delete(moved);
			Assertions.assertThat(awaitChange(source, moved)).as("delete").isTrue();
		} finally {
			source.close();
		}
	}

	@Test
	void doesNotLockSubfoldersSoTheyCanBeMovedWhileWatching(@TempDir Path root)
			throws IOException, InterruptedException {
		FileChangeSource source = open(root);

		try {
			Path folder = root.resolve("albfor-move");
			Files.createDirectories(folder);
			Files.writeString(folder.resolve("inside.jpg"), "x");

			Path target = root.resolve("moved-album");

			// The single root handle must not lock the subfolder: this move (the Explorer
			// Ctrl+X/Ctrl+V case) must succeed while the watch is active.
			Files.move(folder, target);

			Assertions.assertThat(Files.exists(target)).isTrue();
			Assertions.assertThat(awaitChange(source, target)).as("folder move detected").isTrue();
		} finally {
			source.close();
		}
	}

	@Test
	void ignoresChangesOutsideTheRoot(@TempDir Path root, @TempDir Path other)
			throws IOException, InterruptedException {
		FileChangeSource source = open(root);

		try {
			Path outside = other.resolve("elsewhere.jpg");
			Files.writeString(outside, "x");

			// The claim is a negative, so it has to hold over several polls rather than
			// at one instant: a change from outside the root could arrive late.
			await().during(Duration.ofMillis(5 * POLL_PAUSE_MILLIS)).atMost(Duration.ofSeconds(5))
					.pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(POLL_PAUSE_MILLIS))
					.untilAsserted(() -> Assertions.assertThat(source.pollChanges())
							.extracting(FileSystemChange::path).doesNotContain(outside));
		} finally {
			source.close();
		}
	}

	/**
	 * Whether the change turns up within the budget. Answers rather than throws so
	 * each caller keeps the label that says which change it was waiting for.
	 */
	private boolean awaitChange(FileChangeSource source, Path expected) {
		Path normalized = expected.toAbsolutePath().normalize();

		try {
			await().atMost(Duration.ofMillis(MAX_POLLS * POLL_PAUSE_MILLIS)).pollDelay(Duration.ZERO)
					.pollInterval(Duration.ofMillis(POLL_PAUSE_MILLIS))
					.until(() -> source.pollChanges().stream().map(FileSystemChange::path)
							.map(path -> path.toAbsolutePath().normalize()).anyMatch(normalized::equals));

			return true;
		} catch (ConditionTimeoutException _) {
			return false;
		}
	}
}