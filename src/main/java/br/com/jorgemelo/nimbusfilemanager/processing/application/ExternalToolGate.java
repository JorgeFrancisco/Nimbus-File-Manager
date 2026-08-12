package br.com.jorgemelo.nimbusfilemanager.processing.application;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExternalToolCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.ProcessingProperties;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ProcessingMetrics;

/**
 * Caps how many external processes of each {@link ExternalToolCategory} may run
 * at once, independently per category. This is what keeps a parallel inventory
 * from spawning hundreds of ffmpeg/ffprobe processes simultaneously.
 *
 * <p>
 * The gate is orthogonal to the worker pool ({@code ProcessingCoordinator}):
 * the pool bounds how many files are processed concurrently, the gate bounds
 * how many of those may hold a given external process at the same time. A
 * worker blocked on {@link #run} is counted as gate-wait, not execution, in
 * {@link ProcessingMetrics}.
 *
 * <p>
 * Acquisition is interruptible, so cancelling/interrupting a worker thread that
 * is queued behind the limit unblocks it promptly.
 */
@Component
public class ExternalToolGate {

	private final Map<ExternalToolCategory, Semaphore> semaphores = new EnumMap<>(ExternalToolCategory.class);

	public ExternalToolGate(ProcessingProperties properties) {
		// Fair semaphores so long-waiting workers are not starved under contention.
		semaphores.put(ExternalToolCategory.FFMPEG_PHOTO_HASH,
				new Semaphore(properties.ffmpegPhotoHashLimitOrDefault(), true));

		semaphores.put(ExternalToolCategory.FFMPEG_VIDEO_FRAME,
				new Semaphore(properties.ffmpegVideoFrameLimitOrDefault(), true));

		semaphores.put(ExternalToolCategory.FFPROBE_VIDEO,
				new Semaphore(properties.ffprobeVideoLimitOrDefault(), true));

		semaphores.put(ExternalToolCategory.FFMPEG_TRANSCODE,
				new Semaphore(properties.ffmpegTranscodeLimitOrDefault(), true));
	}

	/**
	 * Runs {@code action} while holding one permit of {@code category}. Blocks
	 * (with backpressure) until a permit is free. The permit is always released,
	 * even if the action throws or the thread is interrupted while running it.
	 *
	 * <p>
	 * The permits are the gate's and are shared by everything that runs; where the
	 * waiting and the running are <em>counted</em> is not. The caller says which
	 * execution is paying for this invocation, because several cross this gate at
	 * once and a shared accumulator would report their sum as each of them.
	 *
	 * @param metrics where this invocation's wait and execution are accumulated -
	 * the calling execution's own, never a shared one
	 * @throws InterruptedException if interrupted while waiting for a permit
	 */
	public <T> T run(ExternalToolCategory category, ProcessingMetrics metrics, GatedAction<T> action)
			throws IOException, InterruptedException {
		Semaphore semaphore = semaphores.get(category);

		long waitStart = System.nanoTime();

		semaphore.acquire();

		metrics.recordGateWait(category, System.nanoTime() - waitStart);

		long execStart = System.nanoTime();

		try {
			return action.run();
		} finally {
			metrics.recordExternalExec(category, System.nanoTime() - execStart);

			semaphore.release();
		}
	}
}