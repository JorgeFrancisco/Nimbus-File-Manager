package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.FileSystemChange;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.WatchRecoveryReason;

/**
 * A source of physical file-system change events for the monitored library
 * tree. The inventory watcher polls it every cycle and does not care how the
 * changes are detected - Java {@code WatchService} on most platforms,
 * {@code ReadDirectoryChangesW} on Windows with a USN journal replay for the
 * window the application was down.
 *
 * <p>
 * What a source reports is a {@link FileSystemChange} and not a path, because
 * the sources know more than a path and used to have to throw it away here.
 * They do not all know the same things, so a source fills what it knows and
 * leaves the rest null rather than guessing - see {@link FileSystemChange}.
 *
 * <p>
 * Contract expected by {@code InventoryWatchService}:
 * <ul>
 * <li>{@link #pollChanges()} drains the changes seen since the previous call.
 * It must be non-blocking and DB-free.</li>
 * <li>{@link #takeOfflineBacklog()} hands over the changes the source recovered
 * from before it existed, so that whoever is about to walk the whole tree can
 * take responsibility for them. Optional: what nobody takes is reported by
 * {@link #pollChanges()} like any other change.</li>
 * <li>{@link #consumeRecoveryReason()} reports (and clears) why the source
 * cannot guarantee it saw every change since the last poll. When present, the
 * watcher forces an early full reconcile - this is the single fallback hook
 * every source shares, and the reason is carried so the log can say which of
 * the three very different situations it was.</li>
 * <li>{@link #close()} releases every OS handle held by the source.</li>
 * </ul>
 *
 * <p>
 * Implementations are not required to be internally synchronized: the watcher
 * serializes access on its own monitor (poll vs. reconfigure/close).
 */
public interface FileChangeSource extends Closeable {

	/**
	 * Drains and returns what changed since the previous call. Never blocks;
	 * returns an empty list when nothing changed.
	 */
	List<FileSystemChange> pollChanges();

	/**
	 * Drains and returns what the source recovered about the window before it
	 * existed - on Windows, the USN journal replay of the time the application was
	 * down. Empty for a source that recovers nothing, and empty ever after: the
	 * backlog is produced once, when the source is built.
	 *
	 * <p>
	 * Separate from {@link #pollChanges()} because the two answer different
	 * questions. A live change is news to everybody. A backlog change is old news
	 * that a full walk of the tree would find on its own, so the caller that is
	 * about to run such a walk can say so and spare the library a second pass. It
	 * is a hand-over, not a filter: the caller that takes the backlog owns it, and
	 * nothing else will report it again.
	 */
	List<FileSystemChange> takeOfflineBacklog();

	/**
	 * Why the source cannot guarantee it saw every change since the last poll, or
	 * empty when it can. Reading it clears it, and a present value asks the watcher
	 * for an early full reconcile.
	 *
	 * <p>
	 * The reason travels rather than a flag because the three cases read the same
	 * to the recovery and nothing alike to a person: events the operating system
	 * dropped, a journal that could not be replayed at startup, and a replay that
	 * was itself incomplete.
	 */
	Optional<WatchRecoveryReason> consumeRecoveryReason();

	/** @return the monitored root. */
	Path root();
}