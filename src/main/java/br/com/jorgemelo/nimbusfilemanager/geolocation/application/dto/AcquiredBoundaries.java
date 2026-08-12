package br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto;

import java.util.List;

/**
 * What an acquisition produced: the per-level files ready to import, and whether
 * any of them is different from what the last successful installation used.
 *
 * <p>
 * The second half is the whole point. A conditional download that the server
 * answers with {@code 304} hands back the same path as one that transferred
 * bytes, so a list of paths cannot tell the caller that nothing moved - and the
 * caller, unable to tell, re-imported a worldwide dataset every time. That cost
 * a full delete and reinsert of every boundary, the write-ahead log that comes
 * with it, and the checkpoints that stall everything else running beside it.
 *
 * @param files the per-level GeoJSON files to import, whether freshly downloaded
 * or reused from disk
 * @param changed whether any file's remote content differs from the one the
 * installed dataset was built from; {@code false} only when every level was
 * confirmed unchanged
 */
public record AcquiredBoundaries(List<LeveledBoundaryFile> files, boolean changed) {
}