package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.FileSystemChange;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.FileChangeKind;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.WatchRecoveryReason;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SelfWrittenPath;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.SelfWriteRole;

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
	public List<FileSystemChange> pollChanges() {
		return worthWaking(delegate.pollChanges());
	}

	/**
	 * Filtered exactly like a live poll. The backlog decides whether a second pass
	 * over the library is queued, so it has to be judged by the same rule: a
	 * hidden or application-owned path that would never have woken the watcher
	 * cannot be what keeps it awake now either.
	 */
	@Override
	public List<FileSystemChange> takeOfflineBacklog() {
		return worthWaking(delegate.takeOfflineBacklog());
	}

	/**
	 * The registry is asked once for the whole round rather than once per path: it
	 * lives in the database now, because the process that wrote the file is often
	 * not this one, and a question per path would be a round trip per path.
	 */
	private List<FileSystemChange> worthWaking(List<FileSystemChange> reported) {
		List<FileSystemChange> changed = reported.stream().filter(this::worthAnInventory).toList();

		if (changed.isEmpty()) {
			return changed;
		}

		Set<SelfWrittenPath> announced = selfWrittenPathRegistry
				.announcedAmong(changed.stream().flatMap(change -> accountedBy(change).stream()).distinct().toList());

		return changed.stream().filter(change -> !announced.containsAll(accountedBy(change))).toList();
	}

	/**
	 * What the application would have had to announce for this change to be its
	 * own doing.
	 *
	 * <p>
	 * A change is judged claim by claim and dropped only when every one of them is
	 * accounted for. That is what keeps a rename honest in both directions:
	 * judging it by its destination alone would silence a file the application
	 * moved out of a folder it owns, and judging it by its origin alone, one moved
	 * into one.
	 *
	 * <p>
	 * The role is what keeps a reused path visible. Emptying a path explains it
	 * going quiet; it explains nothing about a file appearing there afterwards,
	 * and a path this product has just freed is one somebody is likely to fill.
	 */
	private static List<SelfWrittenPath> accountedBy(FileSystemChange change) {
		if (change.previousPath() != null) {
			return List.of(new SelfWrittenPath(change.previousPath(), SelfWriteRole.VACATING),
					new SelfWrittenPath(change.path(), SelfWriteRole.OCCUPYING));
		}

		return List.of(new SelfWrittenPath(change.path(),
				change.kind() == FileChangeKind.DELETED ? SelfWriteRole.VACATING : SelfWriteRole.OCCUPYING));
	}

	/**
	 * Both ends of a rename, and the single path of everything else - for the
	 * questions that are about the paths themselves rather than about who wrote
	 * them: a change survives if either end is worth a look.
	 */
	private static List<Path> endpoints(FileSystemChange change) {
		return change.previousPath() == null ? List.of(change.path())
				: List.of(change.path(), change.previousPath());
	}

	private boolean worthAnInventory(FileSystemChange change) {
		return endpoints(change).stream().anyMatch(this::worthALook);
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
	 *
	 * <p>
	 * Deliberately narrower than {@link ScanExclusionService#isExcluded}, which
	 * answers what a scan catalogues rather than what deserves a look. The two
	 * differ in the one direction that matters: {@code isExcluded} also matches on
	 * folder name, and asked without a root it matches every component of an
	 * absolute path - so a library that happens to sit under a folder called
	 * {@code build} or {@code target} would have every change beneath it
	 * suppressed, silently and entirely. What its extension half would buy is the
	 * wake-up for a change that is an excluded file and nothing else, which the
	 * debounce already folds into the pass its neighbours ask for. A filter too
	 * narrow costs redundant work; one too wide costs the change.
	 */
	private boolean worthALook(Path changed) {
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