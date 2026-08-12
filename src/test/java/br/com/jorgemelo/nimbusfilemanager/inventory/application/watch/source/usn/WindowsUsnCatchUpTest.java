package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.constants.UsnReason;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.FileSystemChange;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.PersistedCursor;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.WatchRecoveryReason;

class WindowsUsnCatchUpTest {

	private static final Path ROOT = Path.of("/library").toAbsolutePath();
	private static final String KEY = ROOT.toString();
	private static final int BUFFER = 65_536;
	private static final long JOURNAL = 7L;
	private static final long SUB = 10L;
	/** What a file reference number is unique within; the next volume reuses them. */
	private static final String VOLUME = "volume-under-test";

	private final UsnCursorStore cursorStore = mock(UsnCursorStore.class);
	private final UsnPathResolver resolver = frn -> frn == SUB ? Optional.of(ROOT.resolve("sub")) : Optional.empty();

	private UsnCatchUpResult catchUp(UsnVolume volume) {
		return WindowsUsnCatchUp.catchUp(ROOT, volume, resolver, cursorStore, KEY, VOLUME, BUFFER);
	}

	@Test
	void replaysTheGapAndAdvancesTheCursorWhenValid() {
		when(cursorStore.load(KEY)).thenReturn(Optional.of(new PersistedCursor(JOURNAL, 100L)));

		byte[] batch = UsnRecordBuffers.recordBytes(150L, 100L, SUB, UsnReason.FILE_CREATE,
				UsnRecordBuffers.ATTR_NORMAL, "offline.jpg");
		CatchUpUsnVolume volume = new CatchUpUsnVolume(JOURNAL, 200L, 50L);
		volume.enqueue(new UsnReadResult(180L, batch));

		UsnCatchUpResult result = catchUp(volume);

		Assertions.assertThat(result.offlineChanges()).extracting(FileSystemChange::path)
				.containsExactly(ROOT.resolve("sub").resolve("offline.jpg"));
		Assertions.assertThat(result.recoveryReason()).as("the replay covered its whole window").isNull();
		Assertions.assertThat(volume.firstReadFrom()).isEqualTo(100L);
		verify(cursorStore).save(KEY, JOURNAL, 180L);
	}

	@Test
	void requestsReconcileAndPinsTheCursorWhenThereIsNoSavedCursor() {
		when(cursorStore.load(KEY)).thenReturn(Optional.empty());
		CatchUpUsnVolume volume = new CatchUpUsnVolume(JOURNAL, 200L, 50L);

		UsnCatchUpResult result = catchUp(volume);

		Assertions.assertThat(result.offlineChanges()).isEmpty();
		Assertions.assertThat(result.recoveryReason()).isEqualTo(WatchRecoveryReason.JOURNAL_UNREPLAYABLE);
		Assertions.assertThat(volume.firstReadFrom()).isNull();
		verify(cursorStore).save(KEY, JOURNAL, 200L);
	}

	@Test
	void requestsReconcileWhenTheJournalWasRecreated() {
		when(cursorStore.load(KEY)).thenReturn(Optional.of(new PersistedCursor(1L, 100L)));

		Assertions.assertThat(catchUp(new CatchUpUsnVolume(JOURNAL, 200L, 50L)).recoveryReason())
				.isEqualTo(WatchRecoveryReason.JOURNAL_UNREPLAYABLE);
	}

	@Test
	void requestsReconcileWhenTheCursorAgedOut() {
		when(cursorStore.load(KEY)).thenReturn(Optional.of(new PersistedCursor(JOURNAL, 10L)));

		Assertions.assertThat(catchUp(new CatchUpUsnVolume(JOURNAL, 200L, 50L)).recoveryReason())
				.isEqualTo(WatchRecoveryReason.JOURNAL_UNREPLAYABLE);
	}

	@Test
	void requestsReconcileAndPinsTheCursorWhenTheGapAgesOutMidRead() {
		when(cursorStore.load(KEY)).thenReturn(Optional.of(new PersistedCursor(JOURNAL, 100L)));
		CatchUpUsnVolume volume = new CatchUpUsnVolume(JOURNAL, 200L, 50L);
		volume.failWithGap();

		UsnCatchUpResult result = catchUp(volume);

		Assertions.assertThat(result.offlineChanges()).isEmpty();
		Assertions.assertThat(result.recoveryReason()).as("a gap is the journal being unreplayable, not lost events")
				.isEqualTo(WatchRecoveryReason.JOURNAL_UNREPLAYABLE);
		verify(cursorStore).save(KEY, JOURNAL, 200L);
	}
}