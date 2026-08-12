package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.constants.UsnReason;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.FileSystemChange;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.Interpretation;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.FileChangeKind;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.FileChangeSourceKind;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.FilesystemIdentity;

/**
 * Turns a batch of {@link UsnRecord}s into what happened under the monitored
 * root, plus a "reconcile needed" signal for what a flat event stream cannot
 * represent safely. Pure and native-free: all NTFS access is behind
 * {@link UsnPathResolver}, so every branch is unit-tested with a fake resolver
 * on any platform.
 *
 * <p>
 * <b>Subtree filtering.</b> The journal is volume-wide; a change is inside the
 * library only if its <em>parent</em> directory resolves to a path under the
 * root. Parent resolutions (positive and negative) are cached so the volume's
 * unrelated churn is resolved at most once.
 *
 * <p>
 * <b>Rename/move.</b> NTFS emits a {@code RENAME_OLD_NAME} then a
 * {@code RENAME_NEW_NAME} record for the same file reference, and this pairs
 * them back into one change that carries both ends. That pairing used to be
 * done here and then discarded, leaving two unrelated paths for a reconcile to
 * work out again by hashing content - which is why the pair, and the file
 * reference that proves it is one object, now travel out of here intact.
 *
 * <p>
 * <b>Half a rename.</b> When only one end falls under the root - a file moved
 * in from elsewhere, or out to elsewhere - there is no rename to report, only
 * an arrival or a departure. Both still carry the file reference, so a
 * consumer can recognise the same object turning up on either side of the
 * boundary, or in a later batch.
 *
 * <p>
 * <b>Reconcile fallback.</b> A <em>directory</em> rename/move moves its whole
 * subtree with no per-descendant records. The change is reported, so that the
 * bulk relocation can act on it, and the reconcile is still requested until
 * that path is wired; directory create/delete need no action, because files
 * created inside carry their own records and a deleted tree's files each emit
 * their own delete.
 */
public class UsnChangeInterpreter {

	private static final int MATERIAL_FILE_REASONS = UsnReason.FILE_CREATE | UsnReason.FILE_DELETE
			| UsnReason.DATA_OVERWRITE | UsnReason.DATA_EXTEND | UsnReason.DATA_TRUNCATION;

	private static final int MAX_CACHE_ENTRIES = 20_000;

	private final Path root;
	private final String volumeScope;
	private final UsnPathResolver resolver;

	/**
	 * Parent-FRN -> directory path under root, or null when confirmed outside root.
	 */
	private final Map<Long, Path> directoryCache = new LinkedHashMap<>(256, 0.75f, true) {

		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Path> eldest) {
			return size() > MAX_CACHE_ENTRIES;
		}
	};

	/**
	 * @param volumeScope what the file reference numbers are unique within, since
	 * the next volume reuses the very same numbers. Without it no identity is
	 * reported at all, rather than one that cannot be compared.
	 */
	UsnChangeInterpreter(Path root, String volumeScope, UsnPathResolver resolver) {
		this.root = root.toAbsolutePath().normalize();
		this.volumeScope = volumeScope;
		this.resolver = resolver;
	}

	public Interpretation interpret(List<UsnRecord> records) {
		Set<FileSystemChange> changes = new LinkedHashSet<>();
		Map<Long, UsnRecord> pendingRenameOld = new LinkedHashMap<>();
		boolean reconcileNeeded = false;

		for (UsnRecord entry : records) {
			if (UsnReason.hasAny(entry.reason(), UsnReason.RENAME_OLD_NAME)) {
				pendingRenameOld.put(entry.fileReferenceNumber(), entry);
			} else if (UsnReason.hasAny(entry.reason(), UsnReason.RENAME_NEW_NAME)) {
				reconcileNeeded |= applyRename(pendingRenameOld.remove(entry.fileReferenceNumber()), entry, changes);
			} else {
				applySimple(entry, changes);
			}
		}

		// A RENAME_OLD_NAME whose RENAME_NEW_NAME lands in a later batch: the entry
		// left this location, and the identity it carries is what lets the arrival,
		// whenever it shows up, be recognised as the same object.
		for (UsnRecord old : pendingRenameOld.values()) {
			reconcileNeeded |= applyDeparture(old, changes);
		}

		return new Interpretation(new ArrayList<>(changes), reconcileNeeded);
	}

	/**
	 * @return whether a reconcile is still required, which a directory move always
	 * is: its descendants produce no records of their own.
	 */
	private boolean applyRename(UsnRecord old, UsnRecord neu, Set<FileSystemChange> changes) {
		boolean directory = (old != null && old.directory()) || neu.directory();

		Path newPath = resolve(neu);

		if (newPath == null) {
			return applyDeparture(old, changes);
		}

		Path oldPath = old == null ? null : resolve(old);

		if (oldPath == null) {
			// It came from outside the library: nothing here moved, something here
			// appeared.
			changes.add(change(FileChangeKind.CREATED, newPath, null, neu, directory));

			return directory;
		}

		changes.add(change(FileChangeKind.RENAMED, newPath, oldPath, neu, directory));

		return directory;
	}

	private boolean applyDeparture(UsnRecord old, Set<FileSystemChange> changes) {
		if (old == null) {
			return false;
		}

		Path oldPath = resolve(old);

		if (oldPath == null) {
			return false;
		}

		changes.add(change(FileChangeKind.DELETED, oldPath, null, old, old.directory()));

		return old.directory();
	}

	private void applySimple(UsnRecord entry, Set<FileSystemChange> changes) {
		// A directory create, delete or attribute change needs no action from this
		// method. Only a directory move matters, and that arrives as a pair of RENAME
		// records.
		if (entry.directory() || !UsnReason.hasAny(entry.reason(), MATERIAL_FILE_REASONS)) {
			return;
		}

		Path path = resolve(entry);

		if (path != null) {
			changes.add(change(kindOf(entry.reason()), path, null, entry, false));
		}
	}

	/**
	 * One record's reason is a bitmask that may carry several of these at once - a
	 * file created and written before the journal closed it reports both. The end
	 * state is what matters, so a deletion outranks a creation and a creation
	 * outranks the writes that filled it.
	 */
	private static FileChangeKind kindOf(int reason) {
		if (UsnReason.hasAny(reason, UsnReason.FILE_DELETE)) {
			return FileChangeKind.DELETED;
		}

		if (UsnReason.hasAny(reason, UsnReason.FILE_CREATE)) {
			return FileChangeKind.CREATED;
		}

		return FileChangeKind.MODIFIED;
	}

	/**
	 * The time comes from the record the change is named after, which for a rename
	 * is the arrival and not the departure. Both are written by one operation and
	 * usually agree to the microsecond, but they need not arrive together: a
	 * {@code RENAME_OLD_NAME} whose other half lands in a later batch is reported
	 * as a departure on its own time, and the rename that is eventually recorded
	 * is the moment the entry reached the name it now has.
	 */
	private FileSystemChange change(FileChangeKind kind, Path path, Path previousPath, UsnRecord entry,
			boolean directory) {
		return new FileSystemChange(kind, path, previousPath, identityOf(entry), FileChangeSourceKind.USN_JOURNAL,
				directory, entry.occurredAt());
	}

	/**
	 * The file reference, scoped to the volume that issued it - or nothing, when
	 * the volume could not be named. A number nobody can scope is not a weaker
	 * identity, it is one that names a different file on the next drive.
	 */
	private FilesystemIdentity identityOf(UsnRecord entry) {
		if (volumeScope == null || volumeScope.isBlank()) {
			return null;
		}

		return FilesystemIdentity.windowsFileId(volumeScope, entry.fileReferenceNumber());
	}

	/** @return the entry's absolute path when it is under the root, else null. */
	private Path resolve(UsnRecord entry) {
		return resolveParent(entry.parentFileReferenceNumber()).map(parent -> parent.resolve(entry.fileName()))
				.orElse(null);
	}

	/**
	 * @return the parent directory path when it resolves under the root, else
	 * empty.
	 */
	private Optional<Path> resolveParent(long parentFrn) {
		if (directoryCache.containsKey(parentFrn)) {
			return Optional.ofNullable(directoryCache.get(parentFrn));
		}

		Path underRoot = resolver.resolveDirectory(parentFrn).map(path -> path.toAbsolutePath().normalize())
				.filter(path -> path.startsWith(root)).orElse(null);

		directoryCache.put(parentFrn, underRoot);

		return Optional.ofNullable(underRoot);
	}
}