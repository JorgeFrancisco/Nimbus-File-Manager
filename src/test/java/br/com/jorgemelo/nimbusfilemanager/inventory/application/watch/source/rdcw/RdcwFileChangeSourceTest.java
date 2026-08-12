package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw;

import java.nio.file.Path;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.FileChangeKind;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.FileChangeSourceKind;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.FileSystemChange;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.WatchRecoveryReason;

class RdcwFileChangeSourceTest {

	private static final Path ROOT = Path.of("/library").toAbsolutePath();

	@Test
	void reportsLiveChangesResolvedUnderTheRoot() {
		FakeSeam seam = new FakeSeam();

		seam.enqueue(Notifications.read(Notifications.modified("2024\\a.jpg")));

		try (RdcwFileChangeSource source = new RdcwFileChangeSource(ROOT, seam, null, List.of(), null)) {
			Assertions.assertThat(source.pollChanges()).extracting(FileSystemChange::path)
					.containsExactly(ROOT.resolve("2024").resolve("a.jpg"));
			Assertions.assertThat(source.consumeRecoveryReason()).isEmpty();
		}
	}

	@Test
	void deliversTheUsnCatchUpChangesOnlyOnTheFirstPoll() {
		FakeSeam seam = new FakeSeam();

		seam.enqueue(Notifications.read(Notifications.modified("live.jpg")));
		seam.enqueue(Notifications.read(Notifications.modified("live2.jpg")));

		// The journal replay, which is the one source that knows when a change
		// happened - the live watch never does.
		Path offline = ROOT.resolve("offline.jpg");
		FileSystemChange replayed = new FileSystemChange(FileChangeKind.MODIFIED, offline, null, null,
				FileChangeSourceKind.USN_JOURNAL, false, Instant.parse("2026-08-13T09:00:00Z"));

		try (RdcwFileChangeSource source = new RdcwFileChangeSource(ROOT, seam, null, List.of(replayed), null)) {
			Assertions.assertThat(source.pollChanges()).extracting(FileSystemChange::path)
					.containsExactly(offline, ROOT.resolve("live.jpg"));
			Assertions.assertThat(source.pollChanges()).extracting(FileSystemChange::path)
					.containsExactly(ROOT.resolve("live2.jpg"));
		}
	}

	/**
	 * The catch-up changes go to whoever asks for them first and to nobody else.
	 * Taking them is how an adoption says the full scan it is starting will cover
	 * them, and a poll that reported them again afterwards would put the very
	 * work back that the hand-over exists to avoid. Live notifications are not
	 * part of the bargain and keep arriving.
	 */
	@Test
	void handsTheCatchUpChangesOverOnceAndKeepsReportingLiveOnes() {
		FakeSeam seam = new FakeSeam();

		seam.enqueue(Notifications.read(Notifications.modified("live.jpg")));

		// The journal replay, which is the one source that knows when a change
		// happened - the live watch never does.
		Path offline = ROOT.resolve("offline.jpg");
		FileSystemChange replayed = new FileSystemChange(FileChangeKind.MODIFIED, offline, null, null,
				FileChangeSourceKind.USN_JOURNAL, false, Instant.parse("2026-08-13T09:00:00Z"));

		try (RdcwFileChangeSource source = new RdcwFileChangeSource(ROOT, seam, null, List.of(replayed), null)) {
			Assertions.assertThat(source.takeOfflineBacklog()).extracting(FileSystemChange::path)
					.containsExactly(offline);
			Assertions.assertThat(source.takeOfflineBacklog()).isEmpty();

			Assertions.assertThat(source.pollChanges()).extracting(FileSystemChange::path)
					.containsExactly(ROOT.resolve("live.jpg"));
		}
	}

	/**
	 * An overflow the operating system reported is the one case here where events
	 * were really lost, and it has to keep saying exactly that.
	 */
	@Test
	void propagatesTheSeamOverflowAsLostEvents() {
		FakeSeam seam = new FakeSeam();

		seam.enqueue(Notifications.overflowed());

		try (RdcwFileChangeSource source = new RdcwFileChangeSource(ROOT, seam, null, List.of(), null)) {
			source.pollChanges();

			Assertions.assertThat(source.consumeRecoveryReason()).contains(WatchRecoveryReason.EVENTS_LOST);
			Assertions.assertThat(source.consumeRecoveryReason()).isEmpty();
		}
	}

	/**
	 * The ordinary startup - a journal cursor too old to replay - arrives as
	 * itself. Reporting it as an overflow is what made every start of the
	 * application announce a loss of events that had not happened.
	 */
	@Test
	void carriesTheStartupReasonWithoutCallingItAnOverflow() {
		try (RdcwFileChangeSource source = new RdcwFileChangeSource(ROOT, new FakeSeam(), null, List.of(),
				WatchRecoveryReason.JOURNAL_UNREPLAYABLE)) {
			Assertions.assertThat(source.consumeRecoveryReason()).contains(WatchRecoveryReason.JOURNAL_UNREPLAYABLE);
			Assertions.assertThat(source.consumeRecoveryReason()).isEmpty();
		}
	}

	/** A replay that was itself incomplete is a third thing, and stays distinct. */
	@Test
	void carriesAnIncompleteReplayAsItsOwnReason() {
		try (RdcwFileChangeSource source = new RdcwFileChangeSource(ROOT, new FakeSeam(), null, List.of(),
				WatchRecoveryReason.JOURNAL_REPLAY_INCOMPLETE)) {
			Assertions.assertThat(source.consumeRecoveryReason())
					.contains(WatchRecoveryReason.JOURNAL_REPLAY_INCOMPLETE);
		}
	}

	/**
	 * Both pending at once still asks for one recovery. They lead to the same
	 * pass, so what has to hold is that the answer is one of them and that neither
	 * survives to ask again.
	 */
	@Test
	void reportsTheLiveOverflowWhenAStartupReasonIsAlsoPending() {
		FakeSeam seam = new FakeSeam();

		seam.enqueue(Notifications.overflowed());

		try (RdcwFileChangeSource source = new RdcwFileChangeSource(ROOT, seam, null, List.of(),
				WatchRecoveryReason.JOURNAL_UNREPLAYABLE)) {
			source.pollChanges();

			Assertions.assertThat(source.consumeRecoveryReason()).contains(WatchRecoveryReason.EVENTS_LOST);
			Assertions.assertThat(source.consumeRecoveryReason()).isEmpty();
		}
	}

	/**
	 * A rename is two entries and the pair is never assembled: each side is
	 * reported as its own changed path, in whichever batch it arrives. Pairing
	 * them would buy nothing - the answer to either one is the same debounced
	 * pass - and would need state that survived a buffer boundary, which is
	 * exactly what {@code ReadDirectoryChangesW} does not promise: the two halves
	 * can land in different reads, and this is that case.
	 */
	@Test
	void reportsBothSidesOfARenameEvenWhenTheyLandInDifferentBatches() {
		FakeSeam seam = new FakeSeam();

		seam.enqueue(Notifications.read(Notifications.modified("2024\\was-called.jpg")));
		seam.enqueue(Notifications.read(Notifications.modified("2024\\is-called.jpg")));

		try (RdcwFileChangeSource source = new RdcwFileChangeSource(ROOT, seam, null, List.of(), null)) {
			Assertions.assertThat(source.pollChanges()).extracting(FileSystemChange::path)
					.containsExactly(ROOT.resolve("2024").resolve("was-called.jpg"));
			Assertions.assertThat(source.pollChanges()).extracting(FileSystemChange::path)
					.containsExactly(ROOT.resolve("2024").resolve("is-called.jpg"));
		}
	}

	/**
	 * A folder is reported like anything else. It matters because a folder moved
	 * into the library arrives already full: its files were never created under
	 * the watched tree, so not one of them produces a notification, and the
	 * folder's own path is all the notice they get. Screening directories out
	 * here - which reads as tidying, since directories are not catalogued - is
	 * what would lose them.
	 */
	@Test
	void reportsAFolderPathAndNotOnlyFilePaths() {
		FakeSeam seam = new FakeSeam();

		seam.enqueue(Notifications.read(Notifications.modified("albums\\holiday-2026")));

		try (RdcwFileChangeSource source = new RdcwFileChangeSource(ROOT, seam, null, List.of(), null)) {
			Assertions.assertThat(source.pollChanges()).extracting(FileSystemChange::path)
					.containsExactly(ROOT.resolve("albums").resolve("holiday-2026"));
		}
	}

	@Test
	void exposesTheRootAndClosesTheSeam() {
		FakeSeam seam = new FakeSeam();

		RdcwFileChangeSource source = new RdcwFileChangeSource(ROOT, seam, null, List.of(), null);

		Assertions.assertThat(source.root()).isEqualTo(ROOT);

		source.close();

		Assertions.assertThat(seam.closed()).isTrue();
	}
}