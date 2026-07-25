package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class VideoFingerprintBacklogStartupTest {

	private final VideoFingerprintBacklogAsyncRunner runner = mock(VideoFingerprintBacklogAsyncRunner.class);
	private final VideoFingerprintBacklogStartup startup = new VideoFingerprintBacklogStartup(runner);

	@Test
	void runsTheBacklogWhenWorkRemains() {
		when(runner.start()).thenReturn(true);

		startup.resumeOnStartup();

		verify(runner).run();
	}

	@Test
	void doesNotRunWhenStartIsRefused() {
		when(runner.start()).thenReturn(false);

		startup.resumeOnStartup();

		verify(runner, never()).run();
	}
}