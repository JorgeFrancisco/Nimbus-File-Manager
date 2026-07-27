package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DrainResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintJobStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.FingerprintJobRun;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintJobRunRepository;

@ExtendWith(MockitoExtension.class)
class PhashBacklogAsyncRunnerTest {

	@Mock
	private PhashBacklogService backlogService;

	@Mock
	private FingerprintJobRunRepository jobRunRepository;

	/**
	 * The photo runner exposes the same reading surface as the video one, and only
	 * the video side was covered. The live status is what every screen shows.
	 */
	@Test
	void exposesTheBacklogReadingsTheScreensRender() {
		FingerprintBacklogStatus stored = new FingerprintBacklogStatus(7, 93, 0);

		when(backlogService.status()).thenReturn(stored);

		PhashBacklogAsyncRunner runner = runner();

		Assertions.assertThat(runner.status()).isEqualTo(stored);
		Assertions.assertThat(runner.liveStatus()).isEqualTo(stored);
		Assertions.assertThat(runner.lastError()).isNull();
	}

	private PhashBacklogAsyncRunner runner() {
		return new PhashBacklogAsyncRunner(backlogService, jobRunRepository, Clock.systemDefaultZone());
	}

	@Test
	void startRefusesWhileAPriorityExecutionIsActive() {
		when(backlogService.pausedByActiveExecution()).thenReturn(true);

		Assertions.assertThat(runner().start()).isFalse();

		verify(jobRunRepository, never()).save(any());
	}

	@Test
	void startRefusesWhenNothingIsPending() {
		when(backlogService.pausedByActiveExecution()).thenReturn(false);
		when(backlogService.status()).thenReturn(new FingerprintBacklogStatus(0, 10, 0));

		Assertions.assertThat(runner().start()).isFalse();

		verify(jobRunRepository, never()).save(any());
	}

	@Test
	void startIsIdempotentUnderConcurrentCalls() {
		PhashBacklogAsyncRunner runner = runner();

		when(backlogService.pausedByActiveExecution()).thenReturn(false);
		when(backlogService.status()).thenReturn(new FingerprintBacklogStatus(5, 0, 0));
		when(jobRunRepository.save(any())).thenReturn(runWithId(7L));

		Assertions.assertThat(runner.start()).isTrue();
		Assertions.assertThat(runner.start()).isFalse();
		Assertions.assertThat(runner.isRunning()).isTrue();
	}

	@Test
	void etaIsUnknownUntilEnoughProgressAndElapsedTimeExist() {
		PhashBacklogAsyncRunner runner = runner();

		when(backlogService.pausedByActiveExecution()).thenReturn(false);
		when(backlogService.status()).thenReturn(new FingerprintBacklogStatus(5, 0, 0));
		when(jobRunRepository.save(any())).thenReturn(runWithId(9L));

		Assertions.assertThat(runner.etaSeconds()).isEqualTo(-1);
		Assertions.assertThat(runner.start()).isTrue();
		Assertions.assertThat(runner.etaSeconds()).isEqualTo(-1);
	}

	@Test
	void prepareRebuildClearsOnlyFingerprintsThenStartsTrackedJob() {
		PhashBacklogAsyncRunner runner = runner();

		when(backlogService.pausedByActiveExecution()).thenReturn(false);
		when(backlogService.status()).thenReturn(new FingerprintBacklogStatus(12, 0, 0));
		when(jobRunRepository.save(any())).thenReturn(runWithId(8L));

		Assertions.assertThat(runner.prepareRebuild()).isTrue();

		verify(backlogService).rebuild();

		Assertions.assertThat(runner.isRunning()).isTrue();
	}

	@Test
	void prepareRebuildRefusesWhileInventoryIsActive() {
		when(backlogService.pausedByActiveExecution()).thenReturn(true);

		Assertions.assertThat(runner().prepareRebuild()).isFalse();

		verify(backlogService, never()).rebuild();
	}

	@Test
	void runDrainsAndFinalizesTheJobRun() {
		PhashBacklogAsyncRunner runner = runner();
		FingerprintJobRun run = runWithId(7L);

		when(backlogService.pausedByActiveExecution()).thenReturn(false);
		when(backlogService.status()).thenReturn(new FingerprintBacklogStatus(5, 0, 0));
		when(jobRunRepository.save(any())).thenReturn(run);
		when(jobRunRepository.findById(7L)).thenReturn(Optional.of(run));
		when(backlogService.drainPending(any(), any())).thenAnswer(invocation -> {
			ProgressListener listener = invocation.getArgument(1);
			listener.onProgress(4, 1);

			return new DrainResult(4, 1);
		});

		runner.start();
		runner.run();

		Assertions.assertThat(runner.isRunning()).isFalse();
		Assertions.assertThat(runner.processed()).isEqualTo(4);
		Assertions.assertThat(runner.failed()).isEqualTo(1);
		Assertions.assertThat(run.getStatus()).isEqualTo(FingerprintJobStatus.FINISHED);
		Assertions.assertThat(run.getProcessed()).isEqualTo(4);
		Assertions.assertThat(run.getFailed()).isEqualTo(1);
		Assertions.assertThat(run.getFinishedAt()).isNotNull();
	}

	/**
	 * Do not delete this as "redundant": it is the ONLY deterministic cover of the
	 * {@code @PreDestroy stop()} method, and it exists on purpose.
	 *
	 * <p>
	 * {@code stop()} is a lifecycle destroy hook. Its only other caller is the
	 * Spring container closing a {@code @SpringBootTest} context. That close races
	 * the JaCoCo dump (which happens at JVM exit): sometimes the context is torn
	 * down first and {@code stop()} is recorded as covered, sometimes JaCoCo dumps
	 * first and it is recorded as missed. That race made the suite's method
	 * coverage jitter run-to-run and intermittently dip
	 * below the mandatory gate.
	 *
	 * <p>
	 * Calling {@code stop()} directly here pins its coverage deterministically, and
	 * does so through real behaviour: an in-flight run must observe the stop signal
	 * and finalize as {@code CANCELLED}. Remove this and the coverage
	 * non-determinism comes straight back.
	 */
	@Test
	void stopRequestsCancellationSoAnInFlightRunFinalizesAsCancelled() {
		PhashBacklogAsyncRunner runner = runner();
		FingerprintJobRun run = runWithId(11L);

		when(backlogService.pausedByActiveExecution()).thenReturn(false);
		when(backlogService.status()).thenReturn(new FingerprintBacklogStatus(5, 0, 0));
		when(jobRunRepository.save(any())).thenReturn(run);
		when(jobRunRepository.findById(11L)).thenReturn(Optional.of(run));

		AtomicBoolean drainSawStopSignal = new AtomicBoolean();

		when(backlogService.drainPending(any(), any())).thenAnswer(invocation -> {
			BooleanSupplier stopSignal = invocation.getArgument(0);
			drainSawStopSignal.set(stopSignal.getAsBoolean());

			return new DrainResult(2, 0);
		});

		runner.start();
		runner.stop();
		runner.run();

		Assertions.assertThat(drainSawStopSignal).isTrue();
		Assertions.assertThat(run.getStatus()).isEqualTo(FingerprintJobStatus.CANCELLED);
		Assertions.assertThat(runner.isRunning()).isFalse();
	}

	private FingerprintJobRun runWithId(Long id) {
		return FingerprintJobRun.builder().id(id).status(FingerprintJobStatus.RUNNING).startedAt(LocalDateTime.now())
				.build();
	}
}