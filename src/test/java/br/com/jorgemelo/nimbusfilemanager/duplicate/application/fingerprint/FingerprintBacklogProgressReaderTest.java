package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogProgress;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.execution.application.EtaLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;

/**
 * What a similarity tab is told about the other medium's fingerprint.
 *
 * <p>
 * The photo and video fingerprints no longer compete for the machine: one runs
 * while the other waits. That fixed the contention and left a screen that looks
 * broken - a video backlog frozen at the same number for hours, with nothing on
 * the tab explaining that the machine is busy with photographs.
 *
 * <p>
 * So the panel gained one line, and these fix what it may say. The rule is
 * symmetrical and is about what is <em>running</em>, never about who yields to
 * whom: the order between the two is the queue's, it can change, and a screen
 * asserting it would keep asserting it afterwards.
 */
class FingerprintBacklogProgressReaderTest {

	private final PhashBacklogService phashBacklogService = mock(PhashBacklogService.class);
	private final VideoFingerprintBacklogService videoFingerprintBacklogService = mock(
			VideoFingerprintBacklogService.class);
	private final FingerprintRunReader fingerprintRunReader = mock(FingerprintRunReader.class);

	private FingerprintBacklogProgressReader reader;

	@BeforeEach
	void setUp() {
		lenient().when(phashBacklogService.status()).thenReturn(new FingerprintBacklogStatus(58_000, 42_000, 0));
		lenient().when(videoFingerprintBacklogService.status()).thenReturn(new FingerprintBacklogStatus(5_672, 188, 0));

		reader = new FingerprintBacklogProgressReader(mock(EtaLabels.class), phashBacklogService,
				videoFingerprintBacklogService, fingerprintRunReader);
	}

	/**
	 * The case the whole line exists for: the video tab, its own backlog standing
	 * still, and photographs being fingerprinted.
	 */
	@Test
	void theVideoTabIsToldThatPhotographsAreBeingFingerprinted() {
		running(ExecutionType.FINGERPRINT_PHOTO);

		FingerprintBacklogProgress progress = reader.forTab(ExecutionType.FINGERPRINT_VIDEO);

		Assertions.assertThat(progress.other()).isNotNull();
		Assertions.assertThat(progress.other().percent()).isEqualTo(42);

		// Its own numbers are untouched by any of this.
		Assertions.assertThat(progress.pending()).isEqualTo(5_672);
		Assertions.assertThat(progress.done()).isEqualTo(188);
	}

	/** And the mirror image, because the rule is about running, not about rank. */
	@Test
	void thePhotoTabIsToldThatVideosAreBeingFingerprinted() {
		running(ExecutionType.FINGERPRINT_VIDEO);

		Assertions.assertThat(reader.forTab(ExecutionType.FINGERPRINT_PHOTO).other()).isNotNull();
	}

	/**
	 * A tab whose own fingerprint is running already explains itself; repeating
	 * that something is happening would be noise.
	 */
	@Test
	void saysNothingAboutTheOtherWhenThisTabsOwnFingerprintIsRunning() {
		running(ExecutionType.FINGERPRINT_PHOTO);

		Assertions.assertThat(reader.forTab(ExecutionType.FINGERPRINT_PHOTO).other()).isNull();
	}

	/** With nothing running, there is no other processing to report. */
	@Test
	void inventsNoContextWhenNothingIsRunning() {
		Assertions.assertThat(reader.forTab(ExecutionType.FINGERPRINT_VIDEO).other()).isNull();
		Assertions.assertThat(reader.forTab(ExecutionType.FINGERPRINT_PHOTO).other()).isNull();
	}

	/**
	 * Both running is not a state this queue produces, but the screen does not know
	 * that and must not depend on it: the tab's own panel is enough.
	 */
	@Test
	void saysNothingAboutTheOtherWhenBothAreRunning() {
		running(ExecutionType.FINGERPRINT_PHOTO);
		running(ExecutionType.FINGERPRINT_VIDEO);

		Assertions.assertThat(reader.forTab(ExecutionType.FINGERPRINT_VIDEO).other()).isNull();
	}

	/**
	 * The cost of the line when it is not shown, which is most of the time: the
	 * poll runs every four seconds, and counting a backlog nobody will see would be
	 * four queries per cycle spent on nothing.
	 */
	@Test
	void neverCountsTheOtherBacklogWhenItHasNothingToSayAboutIt() {
		running(ExecutionType.FINGERPRINT_VIDEO);

		reader.forTab(ExecutionType.FINGERPRINT_VIDEO);

		verify(phashBacklogService, never()).status();
	}

	private void running(ExecutionType type) {
		when(fingerprintRunReader.isRunning(type)).thenReturn(true);
	}
}