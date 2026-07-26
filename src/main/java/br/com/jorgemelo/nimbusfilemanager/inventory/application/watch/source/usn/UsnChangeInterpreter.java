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
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.Interpretation;

/**
 * Turns a batch of {@link UsnRecord}s into the set of physical files under the
 * monitored root that changed, plus a "reconcile needed" signal for the cases a
 * flat event stream cannot represent safely. Pure and native-free: all NTFS
 * access is behind {@link UsnPathResolver}, so every branch is unit-tested with
 * a fake resolver on any platform.
 *
 * <p>
 * <b>Subtree filtering.</b> The journal is volume-wide; a change is inside the
 * library only if its <em>parent</em> directory resolves to a path under the
 * root. Parent resolutions (positive and negative) are cached so the volume's
 * unrelated churn is resolved at most once.
 *
 * <p>
 * <b>Rename/move.</b> NTFS emits a {@code RENAME_OLD_NAME} then a
 * {@code RENAME_NEW_NAME} record for the same FRN. A file rename reports both
 * the old and new paths (whichever fall under the root), so a move into, out of
 * or within the library re-syncs correctly.
 *
 * <p>
 * <b>Reconcile fallback.</b> A <em>directory</em> rename/move moves its whole
 * subtree with no per-descendant records, so it cannot be expressed as file
 * changes - it requests a reconcile instead. Directory create/delete need no
 * action: files created inside carry their own records, and a deleted tree's
 * files each emit their own delete.
 */
public class UsnChangeInterpreter {

	private static final int MATERIAL_FILE_REASONS = UsnReason.FILE_CREATE | UsnReason.FILE_DELETE
			| UsnReason.DATA_OVERWRITE | UsnReason.DATA_EXTEND | UsnReason.DATA_TRUNCATION;

	private static final int MAX_CACHE_ENTRIES = 20_000;

	private final Path root;
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

	UsnChangeInterpreter(Path root, UsnPathResolver resolver) {
		this.root = root.toAbsolutePath().normalize();
		this.resolver = resolver;
	}

	public Interpretation interpret(List<UsnRecord> records) {
		Set<Path> changed = new LinkedHashSet<>();
		Map<Long, UsnRecord> pendingRenameOld = new LinkedHashMap<>();
		boolean reconcileNeeded = false;

		for (UsnRecord entry : records) {
			if (UsnReason.hasAny(entry.reason(), UsnReason.RENAME_OLD_NAME)) {
				pendingRenameOld.put(entry.fileReferenceNumber(), entry);
			} else if (UsnReason.hasAny(entry.reason(), UsnReason.RENAME_NEW_NAME)) {
				reconcileNeeded |= applyRename(pendingRenameOld.remove(entry.fileReferenceNumber()), entry, changed);
			} else {
				applySimple(entry, changed);
			}
		}

		// A RENAME_OLD_NAME whose RENAME_NEW_NAME lands in a later batch: the entry
		// left this location, so report the old path (or reconcile if a directory
		// moved).
		for (UsnRecord old : pendingRenameOld.values()) {
			reconcileNeeded |= applyOldName(old, changed);
		}

		return new Interpretation(new ArrayList<>(changed), reconcileNeeded);
	}

	private boolean applyRename(UsnRecord old, UsnRecord neu, Set<Path> changed) {
		if ((old != null && old.directory()) || neu.directory()) {
			return true;
		}

		if (old != null) {
			addResolved(old, changed);
		}

		addResolved(neu, changed);

		return false;
	}

	private boolean applyOldName(UsnRecord old, Set<Path> changed) {
		if (old.directory()) {
			return true;
		}

		addResolved(old, changed);

		return false;
	}

	private void applySimple(UsnRecord entry, Set<Path> changed) {
		// A directory create, delete or attribute change needs no action from this
		// method. Only a directory move matters, and that arrives as a pair of RENAME
		// records.
		if (entry.directory()) {
			return;
		}

		if (UsnReason.hasAny(entry.reason(), MATERIAL_FILE_REASONS)) {
			addResolved(entry, changed);
		}
	}

	private void addResolved(UsnRecord entry, Set<Path> changed) {
		resolveParent(entry.parentFileReferenceNumber())
				.ifPresent(parent -> changed.add(parent.resolve(entry.fileName())));
	}

	/**
	 * @return the parent directory path when it resolves under the root, else
	 *         empty.
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