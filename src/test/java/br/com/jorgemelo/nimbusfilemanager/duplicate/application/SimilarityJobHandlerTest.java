package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * What the two handlers declare is not decoration: the worker reads
 * {@code concurrencyLimit} to size a semaphore per type and {@code resumable} to
 * decide what happens to an execution a dead worker left behind. Both answers
 * are behaviour, so both are asserted.
 *
 * <p>
 * One at a time per type reproduces exactly what the removed runners enforced
 * with a flag - and because the limit is per type, a photo analysis and a video
 * analysis still run side by side, as the old pool of two threads allowed.
 */
class SimilarityJobHandlerTest {

	private final PhotoSimilarityService photoSimilarityService = mock(PhotoSimilarityService.class);
	private final VideoSimilarityService videoSimilarityService = mock(VideoSimilarityService.class);
	private final SimilarityJob similarityJob = mock(SimilarityJob.class);

	private final PhotoSimilarityJobHandler photos = new PhotoSimilarityJobHandler(photoSimilarityService,
			similarityJob);
	private final VideoSimilarityJobHandler videos = new VideoSimilarityJobHandler(videoSimilarityService,
			similarityJob);

	@Test
	void eachHandlerOwnsOneTypeAndRunsOneAnalysisAtATime() {
		Assertions.assertThat(photos.type()).isEqualTo(ExecutionType.SIMILARITY_PHOTO);
		Assertions.assertThat(videos.type()).isEqualTo(ExecutionType.SIMILARITY_VIDEO);

		Assertions.assertThat(photos.concurrencyLimit()).isEqualTo(1);
		Assertions.assertThat(videos.concurrencyLimit()).isEqualTo(1);
	}

	@Test
	void anAbandonedAnalysisIsSafeToRunAgainFromTheStart() {
		// Nothing it wrote is visible until it publishes, so a second attempt cannot
		// be told apart from a first - which is what resumable claims.
		Assertions.assertThat(photos.resumable()).isTrue();
		Assertions.assertThat(videos.resumable()).isTrue();
	}

	/**
	 * Neither holds a tree. Grouping reads fingerprints that already exist and
	 * writes groups; it opens no file of the user's and moves nothing, so a folder
	 * to exclude on would be one invented for the sake of having one - and it would
	 * make an analysis wait for, and block, an organization it never touches.
	 */
	@Test
	void neitherAnalysisTakesAPathLock() {
		Assertions.assertThat(photos.requiresPathLock()).isFalse();
		Assertions.assertThat(videos.requiresPathLock()).isFalse();
	}

	@Test
	void eachHandlerRunsTheJobWithItsOwnAnalyzer() {
		Execution execution = Execution.builder().id(42L).build();
		ClaimedExecution claimed = new ClaimedExecution(42L, ExecutionType.SIMILARITY_PHOTO.name(), null, null, "{}");

		photos.handle(execution, claimed, null);
		videos.handle(execution, claimed, null);

		verify(similarityJob).run(photoSimilarityService, execution, claimed);
		verify(similarityJob).run(videoSimilarityService, execution, claimed);
	}
}