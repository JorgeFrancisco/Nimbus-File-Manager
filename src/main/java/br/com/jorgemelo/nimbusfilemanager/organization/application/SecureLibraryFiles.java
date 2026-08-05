package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileTime;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;
import br.com.jorgemelo.nimbusfilemanager.shared.application.library.LibraryFileMutations;
import lombok.extern.slf4j.Slf4j;

/**
 * The one implementation of {@link LibraryFileMutations}, and therefore the one
 * place in production where a file belonging to the user is changed.
 *
 * <p>
 * It lives beside {@link SecureFileMove} rather than in {@code shared} because
 * the verified move depends on hashing, which belongs to another domain: moving
 * the primitive to {@code shared} would drag that dependency with it and make
 * the shared kernel depend on a domain, which is the one direction the
 * architecture does not allow. The interface has no such problem - it names
 * nothing but paths - so it is the interface that sits in {@code shared}, which
 * is what lets the explorer, the quarantine, the conversion and the backup
 * depend on the capability without depending on this package.
 *
 * <p>
 * Every method announces before it acts. The folder watcher polls within
 * milliseconds, so announcing afterwards loses the race and the application
 * inventories a change it made itself. That ordering used to be each caller's
 * job to remember; here there is one place to get it right.
 */
@Slf4j
@Component
public class SecureLibraryFiles implements LibraryFileMutations {

	private final SecureFileMove secureFileMove;
	private final SelfWrittenPathRegistry selfWrittenPathRegistry;

	public SecureLibraryFiles(SecureFileMove secureFileMove, SelfWrittenPathRegistry selfWrittenPathRegistry) {
		this.secureFileMove = secureFileMove;
		this.selfWrittenPathRegistry = selfWrittenPathRegistry;
	}

	/**
	 * Delegated rather than reimplemented: the baseline, the verification, the
	 * announcement of both ends and the report of a move that left something
	 * behind are already there, and a second copy of that logic is exactly what
	 * this port exists to prevent.
	 */
	@Override
	public void move(Path source, Path target, boolean overwrite, Long executionId) throws IOException {
		secureFileMove.move(source, target, overwrite, executionId);
	}

	@Override
	public boolean rollback(Path from, Path to) {
		return secureFileMove.rollback(from, to);
	}

	@Override
	public void renameDirectory(Path source, Path target, Long executionId) throws IOException {
		selfWrittenPathRegistry.announce(source, executionId);
		selfWrittenPathRegistry.announce(target, executionId);

		Files.move(source, target);
	}

	/**
	 * The retry over the read-only attribute is not a nicety: folders synced from
	 * a phone routinely arrive read-only - every WhatsApp media folder does - and
	 * Windows refuses to delete anything carrying it. A refusal the attribute does
	 * not explain fails again on the retry, and that failure is what the caller
	 * sees.
	 */
	@Override
	public void deleteFile(Path path, Long executionId) throws IOException {
		selfWrittenPathRegistry.announce(path, executionId);

		try {
			Files.delete(path);
		} catch (AccessDeniedException _) {
			clearReadOnly(path);

			Files.delete(path);
		}
	}

	/**
	 * {@code Files.delete} on a directory refuses when it still has contents,
	 * which is the promise this method makes - so the promise is kept by the
	 * file system rather than by a check that could go stale between looking and
	 * deleting.
	 */
	@Override
	public void deleteEmptyDirectory(Path path, Long executionId) throws IOException {
		selfWrittenPathRegistry.announce(path, executionId);

		Files.delete(path);
	}

	@Override
	public void carryModifiedTime(Path path, FileTime modifiedTime, Long executionId) {
		if (modifiedTime == null) {
			return;
		}

		selfWrittenPathRegistry.announce(path, executionId);

		try {
			Files.setLastModifiedTime(path, modifiedTime);
		} catch (IOException exception) {
			log.debug("Could not carry the modified time over to {}", path, exception);
		}
	}

	/**
	 * Best effort: file systems without DOS attributes have nothing to clear, and
	 * there the retry above simply reproduces the original refusal.
	 */
	private void clearReadOnly(Path path) {
		DosFileAttributeView view = Files.getFileAttributeView(path, DosFileAttributeView.class,
				LinkOption.NOFOLLOW_LINKS);

		if (view == null) {
			return;
		}

		try {
			view.setReadOnly(false);
		} catch (IOException exception) {
			log.debug("Could not clear the read-only attribute of {}", path, exception);
		}
	}
}