package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw;

import java.nio.file.Path;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.WatchRecoveryReason;

class RdcwFileChangeSourceTest {

	private static final Path ROOT = Path.of("/library").toAbsolutePath();

	@Test
	void reportsLiveChangesResolvedUnderTheRoot() {
		FakeSeam seam = new FakeSeam();

		seam.enqueue(new RdcwReadResult(List.of("2024\\a.jpg"), false));

		try (RdcwFileChangeSource source = new RdcwFileChangeSource(ROOT, seam, List.of(), null)) {
			Assertions.assertThat(source.pollChangedFiles()).containsExactly(ROOT.resolve("2024").resolve("a.jpg"));
			Assertions.assertThat(source.consumeRecoveryReason()).isEmpty();
		}
	}

	@Test
	void deliversTheUsnCatchUpChangesOnlyOnTheFirstPoll() {
		FakeSeam seam = new FakeSeam();

		seam.enqueue(new RdcwReadResult(List.of("live.jpg"), false));
		seam.enqueue(new RdcwReadResult(List.of("live2.jpg"), false));

		Path offline = ROOT.resolve("offline.jpg");

		try (RdcwFileChangeSource source = new RdcwFileChangeSource(ROOT, seam, List.of(offline), null)) {
			Assertions.assertThat(source.pollChangedFiles()).containsExactly(offline, ROOT.resolve("live.jpg"));
			Assertions.assertThat(source.pollChangedFiles()).containsExactly(ROOT.resolve("live2.jpg"));
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

		seam.enqueue(new RdcwReadResult(List.of("live.jpg"), false));

		Path offline = ROOT.resolve("offline.jpg");

		try (RdcwFileChangeSource source = new RdcwFileChangeSource(ROOT, seam, List.of(offline), null)) {
			Assertions.assertThat(source.takeOfflineBacklog()).containsExactly(offline);
			Assertions.assertThat(source.takeOfflineBacklog()).isEmpty();

			Assertions.assertThat(source.pollChangedFiles()).containsExactly(ROOT.resolve("live.jpg"));
		}
	}

	/**
	 * An overflow the operating system reported is the one case here where events
	 * were really lost, and it has to keep saying exactly that.
	 */
	@Test
	void propagatesTheSeamOverflowAsLostEvents() {
		FakeSeam seam = new FakeSeam();

		seam.enqueue(new RdcwReadResult(List.of(), true));

		try (RdcwFileChangeSource source = new RdcwFileChangeSource(ROOT, seam, List.of(), null)) {
			source.pollChangedFiles();

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
		try (RdcwFileChangeSource source = new RdcwFileChangeSource(ROOT, new FakeSeam(), List.of(),
				WatchRecoveryReason.JOURNAL_UNREPLAYABLE)) {
			Assertions.assertThat(source.consumeRecoveryReason()).contains(WatchRecoveryReason.JOURNAL_UNREPLAYABLE);
			Assertions.assertThat(source.consumeRecoveryReason()).isEmpty();
		}
	}

	/** A replay that was itself incomplete is a third thing, and stays distinct. */
	@Test
	void carriesAnIncompleteReplayAsItsOwnReason() {
		try (RdcwFileChangeSource source = new RdcwFileChangeSource(ROOT, new FakeSeam(), List.of(),
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

		seam.enqueue(new RdcwReadResult(List.of(), true));

		try (RdcwFileChangeSource source = new RdcwFileChangeSource(ROOT, seam, List.of(),
				WatchRecoveryReason.JOURNAL_UNREPLAYABLE)) {
			source.pollChangedFiles();

			Assertions.assertThat(source.consumeRecoveryReason()).contains(WatchRecoveryReason.EVENTS_LOST);
			Assertions.assertThat(source.consumeRecoveryReason()).isEmpty();
		}
	}

	@Test
	void exposesTheRootAndClosesTheSeam() {
		FakeSeam seam = new FakeSeam();

		RdcwFileChangeSource source = new RdcwFileChangeSource(ROOT, seam, List.of(), null);

		Assertions.assertThat(source.root()).isEqualTo(ROOT);

		source.close();

		Assertions.assertThat(seam.closed()).isTrue();
	}
}