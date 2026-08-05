package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

import java.nio.file.Path;
import java.util.Optional;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.PersistedCursor;

/**
 * Reads where the volume's change journal currently ends, without replaying
 * anything.
 *
 * <p>
 * A port of its own rather than a second use of the catch-up, because the two
 * ask the journal different questions: the catch-up asks what happened while
 * the application was down and moves the cursor as a side effect, while this
 * asks only "what number would the next record get", which is the watermark a
 * completed pass is allowed to claim.
 */
public interface UsnWatermarkOpener {

	/**
	 * @return the journal's identity and its current end, or empty when the volume
	 * cannot be opened - not Windows, not NTFS, or no elevation. Empty is an
	 * ordinary answer and means no checkpoint is taken.
	 */
	Optional<PersistedCursor> read(Path root);
}