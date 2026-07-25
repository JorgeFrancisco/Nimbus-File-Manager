package br.com.jorgemelo.nimbusfilemanager.processing.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.processing.application.dto.CategorySnapshot;
import br.com.jorgemelo.nimbusfilemanager.processing.application.dto.Snapshot;
import br.com.jorgemelo.nimbusfilemanager.processing.domain.enums.ExternalToolCategory;

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
		metrics.recordJvmExtraction(400);
		metrics.recordPersistence(250);
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
		assertThat(snapshot.jvmExtractionNanos()).isEqualTo(400);
		assertThat(snapshot.persistenceNanos()).isEqualTo(250);
		assertThat(snapshot.wallClockNanos()).isEqualTo(800);
		assertThat(snapshot.maxConcurrency()).isEqualTo(3);

		CategorySnapshot photo = snapshot.categories().get(ExternalToolCategory.FFMPEG_PHOTO_HASH);

		assertThat(photo.runs()).isEqualTo(1);
		assertThat(photo.gateWaitNanos()).isEqualTo(30);
		assertThat(photo.externalExecNanos()).isEqualTo(70);
	}

	@Test
	void recordsPhotoHashFormatSplit() {
		ProcessingMetrics metrics = new ProcessingMetrics();

		metrics.recordPhotoHashFormat(true);
		metrics.recordPhotoHashFormat(true);
		metrics.recordPhotoHashFormat(false);
		metrics.recordPhotoHashFailure();

		Snapshot snapshot = metrics.snapshot();

		assertThat(snapshot.photoHashJvmDecodable()).isEqualTo(2);
		assertThat(snapshot.photoHashFfmpegOnly()).isEqualTo(1);
		assertThat(snapshot.photoHashFailures()).isEqualTo(1);
	}

	@Test
	void resetClearsEveryCounterAndTimer() {
		ProcessingMetrics metrics = new ProcessingMetrics();

		// Populate every accumulator with a non-zero value so that omitting any single
		// reset() call would leave that field dirty - otherwise a field that starts at
		// 0 makes its reset indistinguishable from a no-op.
		metrics.incExecuted();
		metrics.incCacheAvoided(3);
		metrics.incCancelled();
		metrics.incError();
		metrics.recordQueueWait(10);
		metrics.recordTaskTotal(20);
		metrics.recordJvmExtraction(30);
		metrics.recordPersistence(40);
		metrics.recordWallClock(50);
		metrics.recordGateWait(ExternalToolCategory.FFMPEG_PHOTO_HASH, 60);
		metrics.recordExternalExec(ExternalToolCategory.FFMPEG_PHOTO_HASH, 70);
		metrics.recordGateWait(ExternalToolCategory.FFPROBE_VIDEO, 80);
		metrics.recordExternalExec(ExternalToolCategory.FFPROBE_VIDEO, 90);
		metrics.recordPhotoHashFormat(true);
		metrics.recordPhotoHashFormat(false);
		metrics.recordPhotoHashFailure();
		metrics.updateMaxConcurrency(4);

		metrics.reset();

		Snapshot snapshot = metrics.snapshot();

		assertThat(snapshot.tasksExecuted()).isZero();
		assertThat(snapshot.tasksCacheAvoided()).isZero();
		assertThat(snapshot.tasksCancelled()).isZero();
		assertThat(snapshot.tasksError()).isZero();
		assertThat(snapshot.queueWaitNanos()).isZero();
		assertThat(snapshot.taskTotalNanos()).isZero();
		assertThat(snapshot.jvmExtractionNanos()).isZero();
		assertThat(snapshot.persistenceNanos()).isZero();
		assertThat(snapshot.wallClockNanos()).isZero();
		assertThat(snapshot.photoHashJvmDecodable()).isZero();
		assertThat(snapshot.photoHashFfmpegOnly()).isZero();
		assertThat(snapshot.photoHashFailures()).isZero();
		assertThat(snapshot.maxConcurrency()).isZero();

		for (ExternalToolCategory category : ExternalToolCategory.values()) {
			CategorySnapshot categorySnapshot = snapshot.categories().get(category);

			assertThat(categorySnapshot.runs()).isZero();
			assertThat(categorySnapshot.gateWaitNanos()).isZero();
			assertThat(categorySnapshot.externalExecNanos()).isZero();
		}
	}
}