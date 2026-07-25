package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw;

import java.io.Closeable;

/**
 * The native seam over an open, recursively-watched directory handle
 * ({@code ReadDirectoryChangesW} with {@code bWatchSubtree=TRUE}, overlapped).
 * Every Win32 call lives behind this interface, so the change source above it
 * is pure Java tested with a fake seam - only the JNA implementation is
 * Windows-specific and unverifiable off Windows.
 */
public interface RdcwReadSeam extends Closeable {

	/**
	 * Non-blocking drain of the changes buffered since the last call: parses the
	 * completed {@code ReadDirectoryChangesW} output (if any) and re-arms the
	 * watch. Returns an empty, non-overflowed result when nothing has changed yet.
	 */
	RdcwReadResult poll();

	@Override
	void close();
}