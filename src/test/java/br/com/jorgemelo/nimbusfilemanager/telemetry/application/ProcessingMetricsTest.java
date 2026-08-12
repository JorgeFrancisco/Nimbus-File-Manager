package br.com.jorgemelo.nimbusfilemanager.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExternalToolCategory;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.CategorySnapshot;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.Snapshot;

class ProcessingMetricsTest {

	@Test
	void accumulatesCountsTimesAndMaxConcurrency() {
		ProcessingMetrics metrics = new ProcessingMetrics();

		metrics.incExecuted();
		metrics.incExecuted();
		metrics.incCacheAvoided(5);
		metrics.incCacheAvoided();
		metrics.incCancelled();
		metrics.incError();
		metrics.recordQueueWait(100);
		metrics.recordTaskTotal(1_000);
		metrics.recordWallClock(800);
		metrics.recordGateWait(ExternalToolCategory.FFMPEG_PHOTO_HASH, 30);
		metrics.recordExternalExec(ExternalToolCategory.FFMPEG_PHOTO_HASH, 70);
		metrics.updateMaxConcurrency(3);
		metrics.updateMaxConcurrency(2);

		Snapshot snapshot = metrics.snapshot();

		assertThat(snapshot.tasksExecuted()).isEqualTo(2);
		assertThat(snapshot.tasksCacheAvoided()).isEqualTo(6);
		assertThat(snapshot.tasksCancelled()).isEqualTo(1);
		assertThat(snapshot.tasksError()).isEqualTo(1);
		assertThat(snapshot.queueWaitNanos()).isEqualTo(100);
		assertThat(snapshot.taskTotalNanos()).isEqualTo(1_000);
		assertThat(snapshot.wallClockNanos()).isEqualTo(800);
		assertThat(snapshot.maxConcurrency()).isEqualTo(3);

		CategorySnapshot photo = snapshot.categories().get(ExternalToolCategory.FFMPEG_PHOTO_HASH);

		assertThat(photo.runs()).isEqualTo(1);
		assertThat(photo.gateWaitNanos()).isEqualTo(30);
		assertThat(photo.externalExecNanos()).isEqualTo(70);
	}

	/**
	 * What replaced the clearing. An accumulator used to be shared and cleared
	 * between runs; now each run gets one of its own, so the property worth
	 * asserting is that a fresh one starts empty and that what one accumulates is
	 * invisible to the other.
	 */
	@Test
	void aFreshAccumulatorStartsEmptyAndCannotSeeAnother() {
		ProcessingMetrics first = new ProcessingMetrics();

		first.incExecuted();
		first.incCacheAvoided(3);
		first.incCancelled();
		first.incError();
		first.recordQueueWait(10);
		first.recordTaskTotal(20);
		first.recordWallClock(50);
		first.recordGateWait(ExternalToolCategory.FFMPEG_PHOTO_HASH, 60);
		first.recordExternalExec(ExternalToolCategory.FFMPEG_PHOTO_HASH, 70);
		first.recordGateWait(ExternalToolCategory.FFPROBE_VIDEO, 80);
		first.recordExternalExec(ExternalToolCategory.FFPROBE_VIDEO, 90);
		first.updateMaxConcurrency(4);

		Snapshot snapshot = new ProcessingMetrics().snapshot();

		assertThat(snapshot.tasksExecuted()).isZero();
		assertThat(snapshot.tasksCacheAvoided()).isZero();
		assertThat(snapshot.tasksCancelled()).isZero();
		assertThat(snapshot.tasksError()).isZero();
		assertThat(snapshot.queueWaitNanos()).isZero();
		assertThat(snapshot.taskTotalNanos()).isZero();
		assertThat(snapshot.wallClockNanos()).isZero();
		assertThat(snapshot.maxConcurrency()).isZero();

		for (ExternalToolCategory category : ExternalToolCategory.values()) {
			CategorySnapshot categorySnapshot = snapshot.categories().get(category);

			assertThat(categorySnapshot.runs()).isZero();
			assertThat(categorySnapshot.gateWaitNanos()).isZero();
			assertThat(categorySnapshot.externalExecNanos()).isZero();
		}

		assertThat(first.snapshot().tasksExecuted()).as("the other one kept everything it counted").isEqualTo(1);
	}
}