package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.shared.application.library.LibraryFileMutations;
import lombok.extern.slf4j.Slf4j;

/**
 * Removes directories that were left empty after the organization moved files
 * out of them. It starts at a directory and walks up its parents, deleting each
 * one that is <em>completely</em> empty on the real file system, and stops at
 * the first parent that still holds any content (or at the configured
 * boundary).
 *
 * <p>
 * Safety rules, on purpose:
 * <ul>
 * <li>Emptiness is checked directly on disk with a {@link DirectoryStream},
 * which lists <em>every</em> entry including hidden and system files - never
 * from the catalog. A single hidden {@code Thumbs.db} or {@code desktop.ini}
 * keeps the folder alive.</li>
 * <li>It never crosses the boundary (the organization source root): only
 * directories strictly inside it are eligible, so the source root itself and
 * anything above it are never touched.</li>
 * <li>It never follows or deletes a symbolic link / junction (mirrors the
 * physical-only policy).</li>
 * <li>It is best-effort: any IO problem simply stops the walk without throwing,
 * so a cleanup issue can never undo or fail a move that already succeeded.</li>
 * </ul>
 *
 * <p>
 * Recreation on undo needs no bookkeeping here: moving a file back with
 * {@code SecureFileMove} re-creates its parent directories, so the exact chain
 * removed for a moved file is rebuilt when that file is undone.
 */
@Slf4j
@Service
public class EmptyDirectoryCleaner {

	private final LibraryFileMutations libraryFileMutations;

	public EmptyDirectoryCleaner(LibraryFileMutations libraryFileMutations) {
		this.libraryFileMutations = libraryFileMutations;
	}

	/**
	 * Deletes {@code startDir} and its ancestors while they are empty, stopping at
	 * the first non-empty one or at {@code boundary} (exclusive). Returns the
	 * directories actually removed, deepest first. Never throws: on any problem it
	 * stops and returns what it managed to remove.
	 *
	 * @param executionId the run this tidying belongs to, so the watcher keeps
	 * recognising it as this product's own work for as long as that run
	 * demonstrably holds its paths - an organization of a large tree outlasts any
	 * fixed window
	 */
	List<Path> removeEmptyAncestors(Path startDir, Path boundary, Long executionId) {
		List<Path> removed = new ArrayList<>();

		if (startDir == null || boundary == null) {
			return removed;
		}

		Path boundaryNorm = boundary.toAbsolutePath().normalize();

		Path current = startDir.toAbsolutePath().normalize();

		// Only directories strictly inside the boundary are eligible; equal-to-boundary
		// or outside stops.
		while (current != null && !current.equals(boundaryNorm) && current.startsWith(boundaryNorm)) {
			if (!isRemovableEmptyDirectory(current) || !deleteEmptyDirectory(current, executionId)) {
				break;
			}

			log.info("Removed empty directory left by organization: {}", current);

			removed.add(current);

			current = current.getParent();
		}

		return removed;
	}

	/**
	 * Deletes a single empty directory. Returns {@code true} when removed, or
	 * {@code false} when it raced (something re-created a file) or hit a
	 * permission/lock issue, in which case the caller stops ascending.
	 */
	private boolean deleteEmptyDirectory(Path dir, Long executionId) {
		try {
			libraryFileMutations.deleteEmptyDirectory(dir, executionId);

			return true;
		} catch (IOException e) {
			log.warn("Could not remove empty directory {}", dir, e);

			return false;
		}
	}

	private boolean isRemovableEmptyDirectory(Path dir) {
		try {
			// NOFOLLOW so a symlink/junction is not treated as a directory, and never
			// deleted.
			if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(dir)) {
				return false;
			}

			try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
				// Any entry at all - including hidden/system files - means the folder is not
				// empty.
				return !entries.iterator().hasNext();
			}
		} catch (IOException e) {
			log.warn("Could not inspect directory {} for emptiness; leaving it in place", dir, e);

			return false;
		}
	}
}