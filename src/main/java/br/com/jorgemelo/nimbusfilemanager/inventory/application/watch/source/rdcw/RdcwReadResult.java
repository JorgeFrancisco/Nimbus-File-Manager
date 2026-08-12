package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw;

import java.util.List;

/**
 * The outcome of one non-blocking {@code ReadDirectoryChangesW} drain: the
 * entries the OS reported since the last drain (already decoded by the native
 * seam), plus whether it reported a buffer overflow - too many changes to
 * buffer, after which the watcher forces a reconcile.
 *
 * @param entries the changed entries, in the order Windows wrote them, which is
 * what pairs the two halves of a rename (never null; empty when nothing changed
 * since the last drain).
 * @param overflowed whether change events were dropped and a reconcile is due.
 */
public record RdcwReadResult(List<FileNotifyEntry> entries, boolean overflowed) {

	public RdcwReadResult {
		entries = entries == null ? List.of() : List.copyOf(entries);
	}
}