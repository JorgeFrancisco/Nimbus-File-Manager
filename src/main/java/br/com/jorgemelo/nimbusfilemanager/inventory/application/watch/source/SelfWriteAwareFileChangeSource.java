package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.WatchRecoveryReason;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;

/**
 * Wraps any {@link FileChangeSource} and drops the changes the application
 * itself produced, so writing a file the application already catalogues does
 * not trigger a full inventory of the library. Every other change - and the
 * overflow signal, which is the source's own admission that it may have missed
 * something - passes through untouched.
 *
 * <p>
 * Filtering here rather than in each implementation keeps the rule in one
 * place: the sources already differ (USN journal, {@code WatchService}) and
 * neither should have to know why a change is uninteresting.
 */
class SelfWriteAwareFileChangeSource implements FileChangeSource {

	private final FileChangeSource delegate;
	private final SelfWrittenPathRegistry selfWrittenPathRegistry;
	private final ScanExclusionService scanExclusionService;

	SelfWriteAwareFileChangeSource(FileChangeSource delegate, SelfWrittenPathRegistry selfWrittenPathRegistry,
			ScanExclusionService scanExclusionService) {
		this.delegate = delegate;
		this.selfWrittenPathRegistry = selfWrittenPathRegistry;
		this.scanExclusionService = scanExclusionService;
	}

	@Override
	public List<Path> pollChangedFiles() {
		return worthWaking(delegate.pollChangedFiles());
	}

	/**
	 * Filtered exactly like a live poll. The backlog decides whether a second pass
	 * over the library is queued, so it has to be judged by the same rule: a
	 * hidden or application-owned path that would never have woken the watcher
	 * cannot be what keeps it awake now either.
	 */
	@Override
	public List<Path> takeOfflineBacklog() {
		return worthWaking(delegate.takeOfflineBacklog());
	}

	/**
	 * The registry is asked once for the whole round rather than once per path: it
	 * lives in the database now, because the process that wrote the file is often
	 * not this one, and a question per path would be a round trip per path.
	 */
	private List<Path> worthWaking(List<Path> reported) {
		List<Path> changed = reported.stream().filter(this::worthAnInventory).toList();

		if (changed.isEmpty()) {
			return changed;
		}

		Set<Path> announced = selfWrittenPathRegistry.announcedAmong(changed);

		return changed.stream().filter(path -> !announced.contains(path)).toList();
	}

	/**
	 * The changes the application caused itself and can tell without asking
	 * anybody.
	 *
	 * <p>
	 * A folder the application owns is excluded for as long as it is configured:
	 * quarantine, and the catalog backups - which are deliberately put on a
	 * synchronised drive, often inside the watched library. A backup written there
	 * looks like hundreds of MB of new files arriving, and answering it means
	 * inventorying while the file is still being written.
	 */
	private boolean worthAnInventory(Path changed) {
		return !scanExclusionService.isApplicationOwned(changed) && !isHidden(changed);
	}

	/**
	 * Whether the path, or any folder above it, is hidden.
	 *
	 * <p>
	 * The automatic inventory always runs with hidden files excluded, so waking it
	 * for one is asking it to walk the whole library to catalogue nothing. The
	 * case that forced this: a cloud client synchronising the catalog backup
	 * staged the upload in hidden folders at the root of the same drive, and
	 * every batch of bytes it moved there started an inventory of 146k files.
	 *
	 * <p>
	 * The parents are checked too because the change is usually a file inside such
	 * a folder, and the file itself carries no hidden attribute. A path that can no
	 * longer be read - the usual case for a delete - counts as not hidden, so real
	 * deletions keep reaching the watcher.
	 */
	private boolean isHidden(Path changed) {
		Path root = delegate.root();

		// Only below the watched root, never the root itself or anything above it -
		// the same rule the scanner walks by. Judging absolute ancestors would hide
		// whole libraries: a folder under AppData, which Windows marks hidden, would
		// have every file in it ignored.
		for (Path current = changed; current != null && !current.equals(root); current = current.getParent()) {
			try {
				if (Files.exists(current) && Files.isHidden(current)) {
					return true;
				}
			} catch (IOException _) {
				// Unreadable attributes say nothing; let the change through.
			}
		}

		return false;
	}

	@Override
	public Optional<WatchRecoveryReason> consumeRecoveryReason() {
		return delegate.consumeRecoveryReason();
	}

	@Override
	public Path root() {
		return delegate.root();
	}

	@Override
	public void close() throws IOException {
		delegate.close();
	}
}