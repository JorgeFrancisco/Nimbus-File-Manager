package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch;

import java.nio.file.Path;
import java.time.Instant;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.FileSystemChange;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.FileChangeKind;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.FileChangeSourceKind;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.FilesystemIdentity;

/**
 * File-system changes for tests, said the way a source says them.
 *
 * <p>
 * The watcher stopped reducing what it saw to a list of paths, so a test that
 * used to hand over a path now has to say what happened to it - and, where the
 * source knows, which object it was and when. That is the point of the change
 * and not an inconvenience of it: these build the shape without inventing any
 * of the parts a source does not supply.
 *
 * <p>
 * Nothing here fills in an identity or an instant by default. A source that
 * cannot prove either gives neither, and a test that pretends otherwise is
 * testing a source that does not exist.
 */
public final class Changes {

	public static FileSystemChange created(Path path) {
		return of(FileChangeKind.CREATED, path, null);
	}

	public static FileSystemChange modified(Path path) {
		return of(FileChangeKind.MODIFIED, path, null);
	}

	public static FileSystemChange deleted(Path path) {
		return of(FileChangeKind.DELETED, path, null);
	}

	/** The pair only Windows gives: the name it had and the one it has. */
	public static FileSystemChange renamed(Path previous, Path path) {
		return of(FileChangeKind.RENAMED, path, previous);
	}

	/** A folder appearing, which says nothing about where anything used to be. */
	public static FileSystemChange createdDirectory(Path path) {
		return new FileSystemChange(FileChangeKind.CREATED, path, null, null,
				FileChangeSourceKind.READ_DIRECTORY_CHANGES, true, null);
	}

	public static FileSystemChange renamedDirectory(Path previous, Path path) {
		return new FileSystemChange(FileChangeKind.RENAMED, path, previous, null,
				FileChangeSourceKind.READ_DIRECTORY_CHANGES, true, null);
	}

	/** The same change, carrying the identity of the object it is about. */
	public static FileSystemChange withIdentity(FileSystemChange change, FilesystemIdentity identity) {
		return new FileSystemChange(change.kind(), change.path(), change.previousPath(), identity, change.source(),
				change.directory(), change.occurredAt());
	}

	/** The journal knows when it happened; the live watch does not. */
	public static FileSystemChange fromJournal(FileSystemChange change, Instant occurredAt) {
		return new FileSystemChange(change.kind(), change.path(), change.previousPath(), change.identity(),
				FileChangeSourceKind.USN_JOURNAL, change.directory(), occurredAt);
	}

	private static FileSystemChange of(FileChangeKind kind, Path path, Path previous) {
		return new FileSystemChange(kind, path, previous, null, FileChangeSourceKind.READ_DIRECTORY_CHANGES, false,
				null);
	}

	private Changes() {
	}
}