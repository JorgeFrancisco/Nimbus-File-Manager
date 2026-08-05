package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

import java.nio.file.Path;
import java.util.Optional;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.PersistedCursor;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.UsnJournalProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * Moves the stored journal cursor forward to a point a completed pass has
 * earned.
 *
 * <p>
 * <b>Why this exists.</b> The cursor used to be written in one place only - the
 * catch-up that runs when the change source is created - so it stayed frozen at
 * the position the application started from, however long it then ran. The next
 * start had to replay a window as wide as the whole previous uptime plus the
 * time the machine was off, on a volume where every other program also writes.
 * That window regularly could not be replayed, and a journal that cannot be
 * replayed asks for a reconciliation, so an ordinary restart ended in a
 * reconcile and a re-inventory that found nothing.
 *
 * <p>
 * <b>The rule, and why it cannot skip a change.</b> The watermark is read
 * <em>before</em> a full pass over the watched root begins and stored only if
 * that pass finishes. Take W as the journal's end at that moment:
 * <ul>
 * <li>a change numbered below W happened before the walk started, so the walk
 * read the file in its changed state and the catalog has it;</li>
 * <li>a change numbered at or above W may well have been missed by the walk -
 * and is exactly what a replay starting at W reports next time.</li>
 * </ul>
 * So the two halves meet with an overlap and no gap. Reading the watermark
 * <em>after</em> the pass would be the unsafe version: everything that changed
 * while the tree was being walked would sit below the new cursor and be claimed
 * as covered by a walk that never saw it.
 *
 * <p>
 * Only a full inventory earns a checkpoint. A reconciliation walks the same
 * tree but does not catalogue what it finds - it corrects placements and marks
 * absences - so a file created during the offline window is still unknown after
 * one, and letting it move the cursor would drop that file from the replay that
 * would have found it.
 */
@Slf4j
@Service
public class JournalCheckpoint {

	private final UsnCursorStore cursorStore;
	private final UsnWatermarkOpener watermarkOpener;
	private final UsnJournalProperties properties;

	public JournalCheckpoint(UsnCursorStore cursorStore, UsnWatermarkOpener watermarkOpener,
			UsnJournalProperties properties) {
		this.cursorStore = cursorStore;
		this.watermarkOpener = watermarkOpener;
		this.properties = properties;
	}

	/**
	 * Reads the watermark to store if the pass about to start finishes.
	 *
	 * <p>
	 * Answers empty for everything that is not the watched root: the cursor is
	 * keyed by that root, so its absence is what says this pass is about a folder
	 * whose journal nobody is following - a manual inventory of one subfolder, say,
	 * which proves nothing about the rest of the volume.
	 */
	public Optional<PersistedCursor> capture(Path root) {
		if (root == null || !properties.enabled()) {
			log.info("No journal checkpoint for this pass: the USN journal is turned off");

			return Optional.empty();
		}

		String volumeKey = PathUtils.normalize(root);

		if (cursorStore.load(volumeKey).isEmpty()) {
			log.info("No journal checkpoint for {}: no cursor is stored for it, so this pass is not about the "
					+ "watched library", volumeKey);

			return Optional.empty();
		}

		Optional<PersistedCursor> watermark = watermarkOpener.read(root);

		if (watermark.isEmpty()) {
			log.info("No journal checkpoint for {}: the volume could not be opened to read where the journal ends",
					volumeKey);
		}

		return watermark;
	}

	/**
	 * Stores the watermark, now that the pass behind it has finished.
	 *
	 * <p>
	 * Refuses to move if the journal is not the one the watermark came from, and
	 * refuses to move backwards. Both would widen no window and could narrow one:
	 * a cursor that goes back only costs a replay, a cursor that goes forward
	 * without a pass behind it costs a change nobody looks at again.
	 */
	public void advance(Path root, PersistedCursor watermark) {
		if (root == null || watermark == null) {
			return;
		}

		String volumeKey = PathUtils.normalize(root);

		Optional<PersistedCursor> stored = cursorStore.load(volumeKey);

		if (stored.isEmpty()) {
			log.info("Journal checkpoint for {} not stored: the cursor went away while the pass ran", volumeKey);

			return;
		}

		if (stored.get().journalId() != watermark.journalId()) {
			log.info("Journal checkpoint for {} not stored: the journal is now {} and the watermark came from {}",
					volumeKey, stored.get().journalId(), watermark.journalId());

			return;
		}

		if (stored.get().nextUsn() >= watermark.nextUsn()) {
			log.info("Journal checkpoint for {} not stored: the cursor is already at {} and the watermark is {}",
					volumeKey, stored.get().nextUsn(), watermark.nextUsn());

			return;
		}

		cursorStore.save(volumeKey, watermark.journalId(), watermark.nextUsn());

		log.info("Journal checkpoint for {} advanced to {}: the next start replays from a pass that finished, "
				+ "not from the one that started this run", volumeKey, watermark.nextUsn());
	}
}