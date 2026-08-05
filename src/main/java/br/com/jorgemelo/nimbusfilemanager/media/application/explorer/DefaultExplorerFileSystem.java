package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.application.library.LibraryFileMutations;

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

	private final LibraryFileMutations libraryFileMutations;

	DefaultExplorerFileSystem(LibraryFileMutations libraryFileMutations) {
		this.libraryFileMutations = libraryFileMutations;
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
	public int deleteRecursively(Path path, Long executionId) throws IOException {
		if (!Files.isDirectory(path)) {
			delete(path, executionId);

			return 1;
		}

		int deleted = 0;

		try (Stream<Path> entries = Files.walk(path)) {
			// Reverse order visits children before their folder, so every folder is empty
			// by the time it is removed.
			for (Path entry : (Iterable<Path>) entries.sorted(Comparator.reverseOrder())::iterator) {
				boolean file = !Files.isDirectory(entry);

				delete(entry, executionId);

				if (file) {
					deleted++;
				}
			}
		}

		return deleted;
	}

	@Override
	public void deleteEmptyTree(Path folder, Long executionId) throws IOException {
		if (!listFiles(folder).isEmpty()) {
			return;
		}

		try (Stream<Path> entries = Files.walk(folder)) {
			for (Path entry : (Iterable<Path>) entries.sorted(Comparator.reverseOrder())::iterator) {
				delete(entry, executionId);
			}
		}
	}

	/**
	 * Both the announcement to the watcher and the retry over the read-only
	 * attribute now live in the port, which is where every deletion of a user's
	 * file goes through - here or anywhere else.
	 */
	private void delete(Path path, Long executionId) throws IOException {
		libraryFileMutations.deleteFile(path, executionId);
	}
}