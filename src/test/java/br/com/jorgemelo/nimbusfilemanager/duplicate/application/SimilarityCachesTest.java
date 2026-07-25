package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The whole point of this component is that no caller can update one similarity
 * cache and forget the other, so both tests assert the photo AND the video side
 * are reached.
 */
class SimilarityCachesTest {

	private final PhotoSimilarityService photoSimilarityService = mock(PhotoSimilarityService.class);
	private final VideoSimilarityService videoSimilarityService = mock(VideoSimilarityService.class);
	private final SimilarityCaches caches = new SimilarityCaches(photoSimilarityService, videoSimilarityService);

	@Test
	void invalidateAllShouldClearBothCachedGroupings() {
		caches.invalidateAll();

		verify(photoSimilarityService).invalidateCache();
		verify(videoSimilarityService).invalidateCache();
	}

	@Test
	void evictAllShouldPruneTheRemovedMediaFromBothCachedGroupings() {
		List<UUID> removed = List.of(UUID.randomUUID(), UUID.randomUUID());

		caches.evictAll(removed);

		verify(photoSimilarityService).evictFromCache(removed);
		verify(videoSimilarityService).evictFromCache(removed);
	}
}