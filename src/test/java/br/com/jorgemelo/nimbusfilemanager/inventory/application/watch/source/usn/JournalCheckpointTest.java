package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.PersistedCursor;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.UsnJournalProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * The rule that decides when the stored journal position may move.
 *
 * <p>
 * Every refusal here buys the same thing: a wider replay at the next start.
 * Every wrongly granted advance costs the opposite - a change that no replay
 * will report and no walk has seen - so the tests are written from the side of
 * refusing.
 */
class JournalCheckpointTest {

	private static final long JOURNAL = 4242L;

	private final UsnCursorStore cursorStore = mock(UsnCursorStore.class);
	private final UsnWatermarkOpener opener = mock(UsnWatermarkOpener.class);

	private final JournalCheckpoint checkpoint = new JournalCheckpoint(cursorStore, opener,
			new UsnJournalProperties(true, 65_536));

	/**
	 * The cursor is keyed by the watched root, so its absence says this pass is
	 * about a folder whose journal nobody follows - one subfolder, say, which
	 * proves nothing about the rest of the volume.
	 */
	@Test
	void capturesNothingForARootWithNoStoredCursor(@TempDir Path root) {
		when(cursorStore.load(anyString())).thenReturn(Optional.empty());

		Assertions.assertThat(checkpoint.capture(root)).isEmpty();

		verify(opener, never()).read(any());
	}

	@Test
	void capturesTheJournalEndForTheWatchedRoot(@TempDir Path root) {
		storedCursor(root, JOURNAL, 100L);

		when(opener.read(root)).thenReturn(Optional.of(new PersistedCursor(JOURNAL, 500L)));

		Assertions.assertThat(checkpoint.capture(root)).contains(new PersistedCursor(JOURNAL, 500L));
	}

	/** Disabled by configuration means the journal is not consulted at all. */
	@Test
	void capturesNothingWhenTheJournalIsTurnedOff(@TempDir Path root) {
		JournalCheckpoint disabled = new JournalCheckpoint(cursorStore, opener,
				new UsnJournalProperties(false, 65_536));

		Assertions.assertThat(disabled.capture(root)).isEmpty();

		verify(cursorStore, never()).load(anyString());
	}

	@Test
	void advancesTheCursorWhenTheJournalIsStillTheSameOne(@TempDir Path root) {
		storedCursor(root, JOURNAL, 100L);

		checkpoint.advance(root, new PersistedCursor(JOURNAL, 500L));

		verify(cursorStore).save(PathUtils.normalize(root), JOURNAL, 500L);
	}

	/**
	 * A recreated journal renumbers everything, so a watermark taken from the old
	 * one says nothing about the new one. Leaving the cursor alone lets the next
	 * catch-up notice the change and ask for the reconciliation it exists for.
	 */
	@Test
	void refusesToAdvanceOntoADifferentJournal(@TempDir Path root) {
		storedCursor(root, JOURNAL, 100L);

		checkpoint.advance(root, new PersistedCursor(JOURNAL + 1, 500L));

		verify(cursorStore, never()).save(anyString(), anyLong(), anyLong());
	}

	/**
	 * Never backwards, and never sideways. A watermark that is not ahead of what
	 * is stored has nothing to add, and writing it would only risk narrowing a
	 * window somebody else widened.
	 */
	@Test
	void refusesToMoveTheCursorBackwards(@TempDir Path root) {
		storedCursor(root, JOURNAL, 500L);

		checkpoint.advance(root, new PersistedCursor(JOURNAL, 100L));
		checkpoint.advance(root, new PersistedCursor(JOURNAL, 500L));

		verify(cursorStore, never()).save(anyString(), anyLong(), anyLong());
	}

	/** No cursor to advance is the same answer as an unwatched root. */
	@Test
	void refusesToAdvanceWhenNothingWasStored(@TempDir Path root) {
		when(cursorStore.load(anyString())).thenReturn(Optional.empty());

		checkpoint.advance(root, new PersistedCursor(JOURNAL, 500L));

		verify(cursorStore, never()).save(anyString(), anyLong(), anyLong());
	}

	/**
	 * A pass that captured nothing - no elevation, not NTFS, not the watched root
	 * - must end without writing anything rather than without being called.
	 */
	@Test
	void advancingWithoutAWatermarkDoesNothing(@TempDir Path root) {
		checkpoint.advance(root, null);
		checkpoint.advance(null, new PersistedCursor(JOURNAL, 500L));

		verify(cursorStore, never()).save(anyString(), anyLong(), anyLong());
	}

	private void storedCursor(Path root, long journalId, long nextUsn) {
		when(cursorStore.load(PathUtils.normalize(root)))
				.thenReturn(Optional.of(new PersistedCursor(journalId, nextUsn)));
	}
}