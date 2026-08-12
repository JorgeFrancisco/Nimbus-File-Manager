package br.com.jorgemelo.nimbusfilemanager.inventory.application.dto;

import java.util.List;
import java.util.Optional;

/**
 * What the catalog already knows about one scanned path.
 *
 * <p>
 * Deliberately not a single file. A path can be the last known place of several
 * files at once - one that went missing from it, and one that arrived
 * afterwards - and the old lookup, which answered with one row because the path
 * was unique, is exactly how a newly arrived file inherited the identity of a
 * different one.
 *
 * @param presentFileId the file occupying the path now, if one does. At most one
 * exists: two files present at the same path is the invariant the write path
 * holds
 * @param missingFileIds files whose last known place is this path but which are
 * not there now, oldest first
 */
public record CatalogPathMatch(String inputPath, Long presentFileId, List<Long> missingFileIds) {

	/**
	 * The file this scan should be understood to be, when that can be told from
	 * the path alone.
	 *
	 * <p>
	 * A file present at the path is that file - nothing else can be there. With
	 * nothing present and exactly one file remembered at that path, the reasonable
	 * reading is that it came back. With <em>several</em> remembered there, the
	 * path has stopped being an answer: any choice would be a guess, and guessing
	 * would hand one file's fingerprints and exclusions to another. Empty means
	 * the caller should treat what it found as a file it has not seen before,
	 * which is the only claim still true.
	 */
	public Optional<Long> resolvedFileId() {
		if (presentFileId != null) {
			return Optional.of(presentFileId);
		}

		return missingFileIds.size() == 1 ? Optional.of(missingFileIds.getFirst()) : Optional.empty();
	}

	/**
	 * The path names several files the catalog lost track of, and nothing on disk
	 * says which one is back. Reported rather than resolved: filesystem identity,
	 * once it is carried through, is what settles this.
	 */
	public boolean ambiguous() {
		return presentFileId == null && missingFileIds.size() > 1;
	}
}