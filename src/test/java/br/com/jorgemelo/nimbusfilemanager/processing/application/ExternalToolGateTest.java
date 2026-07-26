package br.com.jorgemelo.nimbusfilemanager.processing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.processing.domain.enums.ExternalToolCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.ProcessingProperties;

class ExternalToolGateTest {

	@Test
	void limitsConcurrentRunsPerCategory() throws Exception {
		ExternalToolGate gate = new ExternalToolGate(new ProcessingProperties(4, 8, 2, 2, 1, 1),
				new ProcessingMetrics());

		AtomicInteger current = new AtomicInteger();
		AtomicInteger maxObserved = new AtomicInteger();

		CountDownLatch release = new CountDownLatch(1);

		Runnable holder = () -> {
			try {
				gate.run(ExternalToolCategory.FFMPEG_PHOTO_HASH, () -> {
					maxObserved.accumulateAndGet(current.incrementAndGet(), Math::max);
					release.await(5, TimeUnit.SECONDS);
					current.decrementAndGet();

					return null;
				});
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		};

		Thread[] threads = new Thread[4];

		for (int i = 0; i < 4; i++) {
			threads[i] = new Thread(holder);
			threads[i].setDaemon(true);
			threads[i].start();
		}

		// Limit is 2, so at most two hold the permit at once; the other two block.
		Thread.sleep(300);

		assertThat(current.get()).isEqualTo(2);
		assertThat(maxObserved.get()).isEqualTo(2);

		release.countDown();

		for (Thread thread : threads) {
			thread.join(5_000);
		}

		assertThat(maxObserved.get()).isEqualTo(2);
	}

	@Test
	void ffmpegAndFfprobeLimitsAreIndependent() throws Exception {
		ExternalToolGate gate = new ExternalToolGate(new ProcessingProperties(4, 8, 1, 2, 1, 1),
				new ProcessingMetrics());

		CountDownLatch ffmpegHeld = new CountDownLatch(1);
		CountDownLatch releaseFfmpeg = new CountDownLatch(1);

		Thread ffmpegHolder = new Thread(() -> {
			try {
				gate.run(ExternalToolCategory.FFMPEG_PHOTO_HASH, () -> {
					ffmpegHeld.countDown();
					releaseFfmpeg.await(5, TimeUnit.SECONDS);

					return null;
				});
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		ffmpegHolder.setDaemon(true);
		ffmpegHolder.start();

		assertThat(ffmpegHeld.await(5, TimeUnit.SECONDS)).isTrue();

		// ffprobe uses a different semaphore, so it must not be blocked by the held
		// ffmpeg permit.
		String result = gate.run(ExternalToolCategory.FFPROBE_VIDEO, () -> "probed");

		assertThat(result).isEqualTo("probed");
		assertThat(ffmpegHolder.isAlive()).isTrue();

		releaseFfmpeg.countDown();
		ffmpegHolder.join(5_000);
	}
}