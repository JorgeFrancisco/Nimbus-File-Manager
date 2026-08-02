package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.shared.application.BackgroundWorkGate;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DrainResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.FingerprintJobRun;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintJobRunRepository;

class VideoFingerprintBacklogAsyncRunnerTest {

	private final VideoFingerprintBacklogService backlogService = mock(VideoFingerprintBacklogService.class);
	private final FingerprintJobRunRepository jobRunRepository = mock(FingerprintJobRunRepository.class);
	private final VideoFingerprintBacklogAsyncRunner runner = new VideoFingerprintBacklogAsyncRunner(backlogService,
			jobRunRepository, Clock.systemDefaultZone(), new BackgroundWorkGate());

	@Test
	void startRefusesWhenNothingIsPending() {
		when(backlogService.pausedByActiveExecution()).thenReturn(false);
		when(backlogService.status()).thenReturn(new FingerprintBacklogStatus(0, 4, 0));

		assertThat(runner.start()).isFalse();
	}

	@Test
	void runDrainsAndFinalizesTheJobRun() {
		when(backlogService.pausedByActiveExecution()).thenReturn(false);
		when(backlogService.status()).thenReturn(new FingerprintBacklogStatus(3, 0, 0));
		when(jobRunRepository.save(any())).thenReturn(FingerprintJobRun.builder().id(7L).build());
		when(jobRunRepository.findById(7L)).thenReturn(Optional.of(FingerprintJobRun.builder().id(7L).build()));
		when(backlogService.drainPending(any(), any())).thenAnswer(invocation -> {
			invocation.<ProgressListener>getArgument(1).onProgress(3, 0);

			return new DrainResult(3, 0);
		});

		assertThat(runner.start()).isTrue();

		runner.run();

		assertThat(runner.isRunning()).isFalse();
		assertThat(runner.processed()).isEqualTo(3);
	}

	@Test
	void runMarksTheJobFailedWhenDrainingThrows() {
		when(backlogService.pausedByActiveExecution()).thenReturn(false);
		when(backlogService.status()).thenReturn(new FingerprintBacklogStatus(3, 0, 0));
		when(jobRunRepository.save(any())).thenReturn(FingerprintJobRun.builder().id(9L).build());
		when(jobRunRepository.findById(9L)).thenReturn(Optional.of(FingerprintJobRun.builder().id(9L).build()));
		when(backlogService.drainPending(any(), any())).thenThrow(new IllegalStateException("kaboom"));

		runner.start();
		runner.run();

		assertThat(runner.isRunning()).isFalse();
		assertThat(runner.lastError()).contains("kaboom");
	}

	@Test
	void exposesDelegatedStatusProgressAndRefusesRebuildWhileInventoryActive() {
		when(backlogService.status()).thenReturn(new FingerprintBacklogStatus(1, 2, 3));

		assertThat(runner.status().done()).isEqualTo(2);
		assertThat(runner.failed()).isZero();
		assertThat(runner.etaSeconds()).isEqualTo(-1);
		assertThat(runner.lastError()).isNull();

		when(backlogService.pausedByActiveExecution()).thenReturn(true);

		assertThat(runner.prepareRebuild()).isFalse();
	}
}