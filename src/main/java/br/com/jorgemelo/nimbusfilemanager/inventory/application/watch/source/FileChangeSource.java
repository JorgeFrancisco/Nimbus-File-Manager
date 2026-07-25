package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.List;

/**
 * A source of physical file-system change events for the monitored library
 * tree. The inventory watcher polls it every cycle and does not care how the
 * changes are detected - Java {@code WatchService} on most platforms, the NTFS
 * USN Change Journal on Windows.
 *
 * <p>
 * Contract expected by {@code InventoryWatchService}:
 * <ul>
 * <li>{@link #pollChangedFiles()} drains the changes seen since the previous
 * call and returns the affected physical files (created, modified, moved-in or
 * deleted). It must be non-blocking and DB-free.</li>
 * <li>{@link #consumeOverflow()} reports (and clears) whether the source lost
 * events or otherwise cannot guarantee it saw every change since the last poll.
 * When true, the watcher forces an early full reconcile - this is the single
 * fallback hook every source shares.</li>
 * <li>{@link #close()} releases every OS handle held by the source.</li>
 * </ul>
 *
 * <p>
 * Implementations are not required to be internally synchronized: the watcher
 * serializes access on its own monitor (poll vs. reconfigure/close).
 */
public interface FileChangeSource extends Closeable {

	/**
	 * Drains and returns the physical files changed since the previous call. Never
	 * blocks; returns an empty list when nothing changed.
	 */
	List<Path> pollChangedFiles();

	/**
	 * Whether the source lost events or cannot guarantee completeness since the
	 * last poll (buffer overflow, an invalidated USN cursor, a recreated journal,
	 * ...). Reading the flag clears it. A {@code true} return asks the watcher for
	 * an early full reconcile.
	 */
	boolean consumeOverflow();

	/** @return the monitored root. */
	Path root();
}