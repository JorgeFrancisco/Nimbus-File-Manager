package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class FingerprintBacklogResumerTest {

	private final PhashBacklogAsyncRunner photoBacklogRunner = mock(PhashBacklogAsyncRunner.class);
	private final VideoFingerprintBacklogAsyncRunner videoBacklogRunner = mock(
			VideoFingerprintBacklogAsyncRunner.class);
	private final FingerprintBacklogResumer resumer = new FingerprintBacklogResumer(photoBacklogRunner,
			videoBacklogRunner);

	/** A conversion competes with photo and video hashing alike, so both resume. */
	@Test
	void resumesBothBacklogs() {
		when(photoBacklogRunner.start()).thenReturn(true);
		when(videoBacklogRunner.start()).thenReturn(true);

		resumer.resume();

		verify(photoBacklogRunner).run();
		verify(videoBacklogRunner).run();
	}

	/**
	 * start() already refuses when there is nothing pending or a run is in flight,
	 * and running anyway would start a second drain over the same queue.
	 */
	@Test
	void runsNothingWhenTheBacklogRefusesToStart() {
		when(photoBacklogRunner.start()).thenReturn(false);
		when(videoBacklogRunner.start()).thenReturn(false);

		resumer.resume();

		verify(photoBacklogRunner, never()).run();
		verify(videoBacklogRunner, never()).run();
	}

	/** One backlog having nothing to do never keeps the other from resuming. */
	@Test
	void resumesTheVideoBacklogEvenWhenThePhotoOneHasNothingToDo() {
		when(photoBacklogRunner.start()).thenReturn(false);
		when(videoBacklogRunner.start()).thenReturn(true);

		resumer.resume();

		verify(photoBacklogRunner, never()).run();
		verify(videoBacklogRunner).run();
	}
}