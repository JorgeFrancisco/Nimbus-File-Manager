package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves an NTFS directory File Reference Number (FRN) to its absolute path.
 * The USN journal is volume-wide and records only carry the <em>parent</em> FRN
 * plus the entry's own name, so this is how a change is located and filtered to
 * the monitored library subtree.
 *
 * <p>
 * The single seam over the native {@code OpenFileById} +
 * {@code GetFinalPathNameByHandle} calls, so the interpreter's filtering logic
 * is testable with a fake resolver. Returns empty when the FRN can no longer be
 * opened (for example the directory was already deleted).
 */
public interface UsnPathResolver {

	/** @return the absolute path of the directory with this FRN, if resolvable. */
	Optional<Path> resolveDirectory(long fileReferenceNumber);
}