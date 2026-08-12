package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.constants.FileNotifyAction;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.FileSystemChange;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.FileChangeKind;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.FileChangeSourceKind;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.FilesystemIdentity;

/**
 * Turns what {@code ReadDirectoryChangesExW} reported into changes under the
 * monitored root: resolving the root-relative names to absolute paths, pairing
 * the two halves of a rename and carrying the object's identity out. Pure and
 * native-free, so every branch is unit-tested on any platform. Back-slash
 * separators from Win32 are accepted; a resolved path that escapes the root
 * (defensive, from a malformed {@code ..}) is dropped.
 *
 * <p>
 * <b>Rename.</b> Paired by file id, not by adjacency. Win32 does write
 * {@code RENAMED_OLD_NAME} immediately before {@code RENAMED_NEW_NAME}, and the
 * plain notification left no other way to pair them - but the extended one
 * carries the id of the object each half is about, and both halves of one
 * rename carry the same id. Pairing on what the two records say about
 * themselves holds whatever order they arrive in.
 *
 * <p>
 * <b>Half a rename.</b> An old name whose new name never arrives is a
 * departure - renamed out of the watched tree - and a new name with no old name
 * is an arrival from outside it. Both keep the identity, so the same object is
 * recognisable on either side of the boundary; neither is promoted to a rename
 * that was not observed.
 */
public class RdcwChangeInterpreter {

	private final Path root;
	private final String volumeScope;

	RdcwChangeInterpreter(Path root, String volumeScope) {
		this.root = root.toAbsolutePath().normalize();
		this.volumeScope = volumeScope;
	}

	public List<FileSystemChange> interpret(List<FileNotifyEntry> entries) {
		Set<FileSystemChange> changes = new LinkedHashSet<>();
		Map<Long, FileNotifyEntry> pendingOldName = new LinkedHashMap<>();

		for (FileNotifyEntry entry : entries) {
			if (entry.action() == FileNotifyAction.RENAMED_OLD_NAME) {
				pendingOldName.put(entry.fileId(), entry);
			} else if (entry.action() == FileNotifyAction.RENAMED_NEW_NAME) {
				applyRename(pendingOldName.remove(entry.fileId()), entry, changes);
			} else {
				applySimple(entry, changes);
			}
		}

		// An old name nothing claimed: the object left the watched tree, and only
		// that much was observed.
		pendingOldName.values().forEach(old -> applyDeparture(old, changes));

		return new ArrayList<>(changes);
	}

	private void applyRename(FileNotifyEntry old, FileNotifyEntry neu, Set<FileSystemChange> changes) {
		Path newPath = resolve(neu);

		if (newPath == null) {
			applyDeparture(old, changes);

			return;
		}

		Path oldPath = old == null ? null : resolve(old);

		FileChangeKind kind = oldPath == null ? FileChangeKind.CREATED : FileChangeKind.RENAMED;

		changes.add(change(kind, newPath, oldPath, neu));
	}

	private void applyDeparture(FileNotifyEntry old, Set<FileSystemChange> changes) {
		Path oldPath = old == null ? null : resolve(old);

		if (oldPath != null) {
			changes.add(change(FileChangeKind.DELETED, oldPath, null, old));
		}
	}

	private void applySimple(FileNotifyEntry entry, Set<FileSystemChange> changes) {
		Path path = resolve(entry);

		if (path == null) {
			return;
		}

		FileChangeKind kind = switch (entry.action()) {
		case FileNotifyAction.ADDED -> FileChangeKind.CREATED;
		case FileNotifyAction.REMOVED -> FileChangeKind.DELETED;
		default -> FileChangeKind.MODIFIED;
		};

		changes.add(change(kind, path, null, entry));
	}

	private FileSystemChange change(FileChangeKind kind, Path path, Path previousPath, FileNotifyEntry entry) {
		// No time: a FILE_NOTIFY_EXTENDED_INFORMATION carries none, and putting the
		// clock here would dress a processing instant as the operating system's.
		return new FileSystemChange(kind, path, previousPath, identityOf(entry),
				FileChangeSourceKind.READ_DIRECTORY_CHANGES, entry.directory(), null);
	}

	/**
	 * The file id, scoped to the volume that issued it - or nothing, when the
	 * volume could not be named. A number nobody can scope is not a weaker
	 * identity, it is one that names a different file on the next drive.
	 */
	private FilesystemIdentity identityOf(FileNotifyEntry entry) {
		if (volumeScope == null || volumeScope.isBlank()) {
			return null;
		}

		return FilesystemIdentity.windowsFileId(volumeScope, entry.fileId());
	}

	private Path resolve(FileNotifyEntry entry) {
		Path resolved = root.resolve(entry.relativePath().replace('\\', '/')).normalize();

		return resolved.startsWith(root) ? resolved : null;
	}
}