package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;

/**
 * The real disk behind {@link ExplorerFileSystem}. No walk here follows links:
 * a junction inside the tree is removed as the link it is, never followed into
 * whatever it points at, which is what keeps a delete from escaping the folder
 * the user picked.
 *
 * <p>
 * Every path is announced to {@link SelfWrittenPathRegistry} before it goes.
 * The watcher would otherwise see each removal as a change from outside and
 * wake the inventory once per file - the same reason {@code SecureFileMove}
 * announces both ends of a move, which is why organizing, deduplicating,
 * converting and quarantining are already silent.
 */
@Component
class DefaultExplorerFileSystem implements ExplorerFileSystem {

	private final SelfWrittenPathRegistry selfWrittenPathRegistry;

	DefaultExplorerFileSystem(SelfWrittenPathRegistry selfWrittenPathRegistry) {
		this.selfWrittenPathRegistry = selfWrittenPathRegistry;
	}

	@Override
	public boolean isDirectory(Path path) {
		return Files.isDirectory(path);
	}

	@Override
	public List<Path> listFiles(Path folder) throws IOException {
		try (Stream<Path> entries = Files.walk(folder)) {
			return entries.filter(Files::isRegularFile).toList();
		}
	}

	@Override
	public int deleteRecursively(Path path) throws IOException {
		if (!Files.isDirectory(path)) {
			delete(path);

			return 1;
		}

		int deleted = 0;

		try (Stream<Path> entries = Files.walk(path)) {
			// Reverse order visits children before their folder, so every folder is empty
			// by the time it is removed.
			for (Path entry : (Iterable<Path>) entries.sorted(Comparator.reverseOrder())::iterator) {
				boolean file = !Files.isDirectory(entry);

				delete(entry);

				if (file) {
					deleted++;
				}
			}
		}

		return deleted;
	}

	@Override
	public void deleteEmptyTree(Path folder) throws IOException {
		if (!listFiles(folder).isEmpty()) {
			return;
		}

		try (Stream<Path> entries = Files.walk(folder)) {
			for (Path entry : (Iterable<Path>) entries.sorted(Comparator.reverseOrder())::iterator) {
				delete(entry);
			}
		}
	}

	/**
	 * Announced before the removal, never after: the watcher can poll the event
	 * within milliseconds, so registering afterwards would lose the race and the
	 * inventory would run for a file the application itself just deleted.
	 */
	private void delete(Path path) throws IOException {
		selfWrittenPathRegistry.announce(path);

		try {
			Files.delete(path);
		} catch (AccessDeniedException _) {
			// Windows refuses to delete anything carrying the read-only attribute, and
			// folders synced from a phone routinely arrive with it - the WhatsApp media
			// folders are all read-only. Clearing it and retrying is what a file manager
			// is expected to do. A refusal the attribute does not explain simply fails
			// again on the retry, and that failure is what the caller sees.
			clearReadOnly(path);

			Files.delete(path);
		}
	}

	/**
	 * Best effort: filesystems without DOS attributes (anything but Windows) have
	 * nothing to clear, and there the retry above just reproduces the original
	 * refusal.
	 */
	private void clearReadOnly(Path path) throws IOException {
		DosFileAttributeView attributes = Files.getFileAttributeView(path, DosFileAttributeView.class);

		if (attributes != null) {
			attributes.setReadOnly(false);
		}
	}
}