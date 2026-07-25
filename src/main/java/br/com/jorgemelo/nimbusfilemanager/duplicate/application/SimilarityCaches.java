package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.Collection;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Invalidates or prunes the photo AND video similarity caches together. Every
 * place that changes what the Duplicados screen should show - excluding or
 * restoring a file/folder from comparison, quarantining duplicates - must
 * affect both tabs, so routing it through one component keeps the two caches in
 * step and makes it impossible to update one and forget the other.
 */
@Component
public class SimilarityCaches {

	private final PhotoSimilarityService photoSimilarityService;
	private final VideoSimilarityService videoSimilarityService;

	public SimilarityCaches(PhotoSimilarityService photoSimilarityService,
			VideoSimilarityService videoSimilarityService) {
		this.photoSimilarityService = photoSimilarityService;
		this.videoSimilarityService = videoSimilarityService;
	}

	/** Clears both cached groupings entirely (e.g. exclusion lists changed). */
	public void invalidateAll() {
		photoSimilarityService.invalidateCache();
		videoSimilarityService.invalidateCache();
	}

	/**
	 * Prunes the given (just soft-deleted) media from both cached groupings, so the
	 * screen reflects the deletion without a full recompute.
	 */
	public void evictAll(Collection<UUID> removedPublicIds) {
		photoSimilarityService.evictFromCache(removedPublicIds);
		videoSimilarityService.evictFromCache(removedPublicIds);
	}
}