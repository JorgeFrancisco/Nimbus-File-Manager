package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static br.com.jorgemelo.nimbusfilemanager.duplicate.application.SyntheticVideoSignatures.frame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedGroup;

/**
 * What a stopped video run leaves behind, and whether repeating it is safe.
 *
 * <p>
 * The queue, the publisher and the BUILDING-to-ACTIVE promotion are shared with
 * photos and already held to their promises elsewhere -
 * {@code SimilarityCancellationIntegrationTest} runs its rebuild case over both
 * media, and {@code SimilarityRegroupPublicationIntegrationTest} holds the
 * guarded promotion. Repeating those here would be testing the same code twice.
 *
 * <p>
 * What is <em>not</em> covered there is the part only videos have: a comparison
 * that reports progress from inside two different builders, one of which loads
 * frames in a second pass. Cancellation is noticed through that callback, so
 * where the callback is invoked decides what has already been written when the
 * run stops - and the answer has to be "nothing that a later run could mistake
 * for work already done".
 *
 * <p>
 * The invariant every case below ends on is the same one: whatever a stopped run
 * left, a later run converges to the answer a full rebuild gives. That is the
 * only thing that makes leftovers safe, and it is asserted against a rebuild
 * rather than against a written-down expectation.
 */
class VideoSimilarityCancellationTest {

	private static final int THRESHOLD = 70;

	/**
	 * Stopped before a single pair was looked at: nothing is written, and nothing
	 * claims to have been incorporated.
	 */
	@Test
	void aRebuildStoppedBeforeItComparedAnythingWritesNothing() {
		VideoSimilarityLibrary library = seeded();

		VideoSimilarityService service = library.service();

		SimilarityProgressCallback cancelling = stopAfter(0);

		assertThatThrownBy(() -> service.analyze(THRESHOLD, cancelling))
				.isInstanceOf(IllegalStateException.class);

		assertThat(library.relationCount()).isZero();
		assertThat(library.covered()).isEmpty();

		assertThat(repeatConverges(library)).isTrue();
	}

	/**
	 * Stopped in the middle of the rebuild's pair walk. The builder reports per row
	 * of the outer loop, so this lands with some pairs compared and the rest not -
	 * and still nothing may be stored, because a partial set of relations paired
	 * with coverage would be a claim the run cannot honour.
	 */
	@Test
	void aRebuildStoppedWhileComparingWritesNothing() {
		// More videos than the builder's reporting cadence, so the second callback
		// lands with most rows compared and the rest not - which is the only way to
		// stop genuinely inside the walk rather than at one of its ends.
		VideoSimilarityLibrary library = crowded(70);

		VideoSimilarityService service = library.service();

		SimilarityProgressCallback cancelling = stopAfter(1);

		assertThatThrownBy(() -> service.analyze(THRESHOLD, cancelling))
				.isInstanceOf(IllegalStateException.class);

		assertThat(library.relationCount()).isZero();
		assertThat(library.covered()).isEmpty();

		assertThat(repeatConverges(library)).isTrue();
	}

	/**
	 * The same for an arrival, which stops inside the gate enumeration - the phase
	 * that decides which videos need their frames read at all.
	 */
	@Test
	void anArrivalStoppedWhileEnumeratingPairsWritesNothing() {
		VideoSimilarityLibrary library = analysed();

		library.video(4, same(0));

		int covered = library.covered().size();

		VideoSimilarityService service = library.service();

		SimilarityProgressCallback cancelling = stopAfter(1);

		assertThatThrownBy(() -> service.add(THRESHOLD, cancelling))
				.isInstanceOf(IllegalStateException.class);

		assertThat(library.covered()).as("the newcomer was not marked incorporated").hasSize(covered);

		assertThat(repeatConverges(library)).isTrue();
	}

	/**
	 * Stopped after the frames were loaded and the comparison was under way: the
	 * approval pass reports once the first pair has been scored, so this lands with
	 * SSIM having run for one pair and not for the rest.
	 */
	@Test
	void anArrivalStoppedWhileComparingFramesWritesNothing() {
		VideoSimilarityLibrary library = analysed();

		library.video(4, same(0));
		library.video(5, same(0));

		int covered = library.covered().size();

		VideoSimilarityService service = library.service();

		SimilarityProgressCallback cancelling = stopAfter(2);

		assertThatThrownBy(() -> service.add(THRESHOLD, cancelling))
				.isInstanceOf(IllegalStateException.class);

		assertThat(library.covered()).hasSize(covered);

		assertThat(repeatConverges(library)).isTrue();
	}

	/**
	 * The case where leftovers are real: the arrival finished comparing and wrote
	 * its relations and coverage, and only then was the run stopped - during the
	 * regroup that follows.
	 *
	 * <p>
	 * Those leftovers are safe, and this is why: the relations are facts about
	 * pairs that really were evaluated, and the coverage names exactly the videos
	 * every one of whose pairs was evaluated - the writer makes the two atomic. So
	 * a later run skips them legitimately rather than by accident, and arrives
	 * where a rebuild would.
	 */
	@Test
	void anArrivalStoppedAfterPersistingKeepsWorkThatIsSafeToKeep() {
		VideoSimilarityLibrary library = analysed();

		library.video(4, same(0));

		VideoSimilarityService service = library.service();

		SimilarityProgressCallback cancelling = stopAfterPersisted();

		assertThatThrownBy(() -> service.add(THRESHOLD, cancelling))
				.isInstanceOf(IllegalStateException.class);

		assertThat(library.covered()).as("the newcomer was compared against everything covered, so it is covered")
				.containsExactly(1L, 2L, 3L, 4L);
		assertThat(library.approvedPairs()).contains("1-4", "2-4");

		assertThat(repeatConverges(library)).isTrue();
	}

	/**
	 * Coverage is never granted for a video whose pairs were not evaluated, however
	 * the run ended. Asserted over every stopping point rather than over one,
	 * because the claim is about the whole path and not about a lucky offset.
	 */
	@Test
	void coverageNeverOutrunsTheRelationsItAccountsFor() {
		for (int stopAt = 0; stopAt < 12; stopAt++) {
			VideoSimilarityLibrary library = analysed();

			library.video(4, same(0));
			library.video(5, same(0));

			try {
				library.service().add(THRESHOLD, stopAfter(stopAt));
			} catch (IllegalStateException _) {
				// The stop itself is the point; what it left behind is what is asserted.
			}

			// The newcomers are identical to the videos already covered, so a newcomer
			// that is covered must carry the relations that coverage accounts for. A
			// coverage row without them would be the one claim this table may not get
			// wrong: a later run would skip the video and the pair would be evaluated by
			// nobody, for good.
			for (long covered : library.covered()) {
				if (covered >= 4L) {
					assertThat(library.approvedPairs())
							.as("stopped at %d, so the pairs its coverage promises exist", stopAt)
							.contains("1-" + covered, "2-" + covered, "3-" + covered);
				}
			}

			assertThat(repeatConverges(library)).as("stopped at %d", stopAt).isTrue();
		}
	}

	/**
	 * Two arrivals over the same family, run one after the other without anything
	 * new in between - which is what the queue's 1 + 1 rule lets happen when two
	 * requests are coalesced into a successor. The second finds nothing to
	 * incorporate, adds nothing, and publishes the same answer.
	 */
	@Test
	void aSecondArrivalOverTheSameFamilyAddsNothing() {
		VideoSimilarityLibrary library = analysed();

		library.video(4, same(0));

		List<AnalyzedGroup> first = library.service().add(THRESHOLD, silent()).groups();

		int approved = library.relationCount();

		assertThat(library.service().add(THRESHOLD, silent()).groups()).isEqualTo(first);
		assertThat(library.relationCount()).isEqualTo(approved);
	}

	/**
	 * A video that arrived while an earlier run was working is absorbed by the next
	 * one: it was never covered, so it is still a newcomer, and the answer lands
	 * where a rebuild over the final set lands.
	 */
	@Test
	void aLaterRunAbsorbsWhatArrivedDuringTheEarlierOne() {
		VideoSimilarityLibrary library = analysed();

		library.video(4, same(0));

		VideoSimilarityService service = library.service();

		SimilarityProgressCallback cancelling = stopAfterPersisted();

		assertThatThrownBy(() -> service.add(THRESHOLD, cancelling))
				.isInstanceOf(IllegalStateException.class);

		// It landed while the stopped run was working, so nothing ever compared it.
		library.video(5, same(0));

		assertThat(repeatConverges(library)).isTrue();
		assertThat(library.covered()).contains(5L);
	}

	/**
	 * Whatever the stopped run left, running again reaches the answer a rebuild
	 * over the same final set reaches - which is the only thing that makes any
	 * leftover safe.
	 */
	private boolean repeatConverges(VideoSimilarityLibrary library) {
		List<AnalyzedGroup> incremental = library.service().add(THRESHOLD, silent()).groups();

		List<AnalyzedGroup> rebuilt = library.copy().service().analyze(THRESHOLD, silent()).groups();

		return incremental.equals(rebuilt);
	}

	/**
	 * A callback that stops the run once it has been called enough times, which is
	 * how a cancellation reaches a comparison in production: the progress callback
	 * is where the worker notices, and it stops by throwing.
	 */
	private SimilarityProgressCallback stopAfter(int calls) {
		AtomicInteger seen = new AtomicInteger();

		return (_, _) -> {
			if (seen.getAndIncrement() >= calls) {
				throw new IllegalStateException("cancelled");
			}
		};
	}

	/**
	 * A callback that lets the comparison finish and stops the run afterwards, so
	 * the relations and the coverage are already written when it lands.
	 */
	private SimilarityProgressCallback stopAfterPersisted() {
		AtomicInteger seen = new AtomicInteger();

		return (done, total) -> {
			if (done == total && total > 0 && seen.incrementAndGet() > 1) {
				throw new IllegalStateException("cancelled");
			}
		};
	}

	private VideoSimilarityLibrary seeded() {
		VideoSimilarityLibrary library = new VideoSimilarityLibrary();

		library.video(1, same(0));
		library.video(2, same(0));
		library.video(3, same(0));

		return library;
	}

	/** More videos than the builder reports per row, so a stop can land mid-walk. */
	private VideoSimilarityLibrary crowded(int size) {
		VideoSimilarityLibrary library = new VideoSimilarityLibrary();

		for (int index = 1; index <= size; index++) {
			library.video(index, same(0));
		}

		return library;
	}

	private VideoSimilarityLibrary analysed() {
		VideoSimilarityLibrary library = seeded();

		library.service().analyze(THRESHOLD, silent());

		return library;
	}

	private SimilarityProgressCallback silent() {
		return (_, _) -> {
		};
	}

	private int[] same(int level) {
		int[] frames = new int[FfmpegLanczosFramesPhashAlgorithm.FRAME_SAMPLES];

		for (int index = 0; index < frames.length; index++) {
			frames[index] = frame(index, level);
		}

		return frames;
	}
}