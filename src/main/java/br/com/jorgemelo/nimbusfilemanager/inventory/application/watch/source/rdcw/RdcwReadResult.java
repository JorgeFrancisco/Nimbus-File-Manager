package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw;

import java.util.List;

/**
 * The outcome of one non-blocking {@code ReadDirectoryChangesW} drain: the
 * changed entries' paths relative to the watched root (already parsed by the
 * native seam), plus whether the OS reported a buffer overflow since the last
 * drain (too many changes to buffer - the watcher then forces a reconcile).
 *
 * @param relativePaths the changed paths relative to the root (never null;
 *                      empty when nothing changed since the last drain).
 * @param overflowed    whether change events were dropped and a reconcile is
 *                      due.
 */
public record RdcwReadResult(List<String> relativePaths, boolean overflowed) {

	public RdcwReadResult {
		relativePaths = relativePaths == null ? List.of() : List.copyOf(relativePaths);
	}
}