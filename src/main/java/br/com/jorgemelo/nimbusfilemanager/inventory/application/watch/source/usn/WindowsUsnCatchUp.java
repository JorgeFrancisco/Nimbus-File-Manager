package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.PersistedCursor;

/**
 * Reads the USN journal once at startup to recover the changes made while the
 * application was down, then advances the persisted cursor. Pure orchestration
 * over the {@link UsnVolume} seam, so the cursor-validity and gap decisions are
 * unit-tested with fakes on any platform - the USN journal is used only for
 * this catch-up; the live events come from {@code ReadDirectoryChangesW}.
 *
 * <ul>
 * <li>Cursor present, same journal and still retained → replay the gap and
 * report the offline changes.</li>
 * <li>Otherwise (no cursor, journal recreated, or the USN aged out) → cannot
 * replay: report a reconcile and pin the cursor at the journal's current end so
 * the next start replays from there.</li>
 * </ul>
 */
public final class WindowsUsnCatchUp {

	private WindowsUsnCatchUp() {
	}

	public static UsnCatchUpResult catchUp(Path root, UsnVolume volume, UsnPathResolver resolver,
			UsnCursorStore cursorStore, String volumeKey, int bufferBytes) {
		Optional<PersistedCursor> saved = cursorStore.load(volumeKey);

		boolean canReplay = saved.isPresent() && saved.get().journalId() == volume.journalId()
				&& saved.get().nextUsn() >= volume.lowestValidUsn();

		long startUsn = canReplay ? saved.get().nextUsn() : volume.nextUsn();

		UsnJournalReader reader = new UsnJournalReader(volume, new UsnChangeInterpreter(root, resolver), bufferBytes,
				startUsn);

		List<Path> offlineChanges = List.of();

		boolean reconcileNeeded = !canReplay;

		try {
			if (canReplay) {
				offlineChanges = reader.poll();
				reconcileNeeded = reader.consumeOverflow();
			}
		} catch (UsnGapException _) {
			// The gap aged out mid-read: cannot replay, so reconcile and pin the cursor at
			// the journal's end. The real-time watch is unaffected.
			reader.resetTo(volume.nextUsn());

			offlineChanges = List.of();
			reconcileNeeded = true;
		}

		cursorStore.save(volumeKey, volume.journalId(), reader.nextUsn());

		return new UsnCatchUpResult(offlineChanges, reconcileNeeded);
	}
}