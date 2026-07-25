package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.CachedGroups;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PageUtils;

/**
 * Neutral cache for a heavy similarity grouping, keyed by the similarity
 * threshold and tagged with the fingerprint signature it was computed from. The
 * result is served (and paginated) only while that signature still holds, so
 * re-opening or paginating a tab is instant, and a change in the fingerprint
 * set transparently forces a recompute.
 *
 * <p>
 * Shared by the photo and video grouping services: the media-specific parts
 * (how the signature is derived, how a stale group is dropped on delete) are
 * passed in, everything mechanical (the map, signature check, pagination) lives
 * here once.
 *
 * @param <T> the group response type
 */
class SimilarityGroupCache<T> {

	private final Map<Integer, CachedGroups<T>> cache = new ConcurrentHashMap<>();
	private final Supplier<String> signature;
	private final IntSupplier maxPageSize;

	public SimilarityGroupCache(Supplier<String> signature, IntSupplier maxPageSize) {
		this.signature = signature;
		this.maxPageSize = maxPageSize;
	}

	/** Whether the grouping for this key is cached for the current signature. */
	public boolean isCached(int key) {
		CachedGroups<T> cached = cache.get(key);

		return cached != null && cached.signature().equals(signature.get());
	}

	/**
	 * Page of the cached grouping for this key, or empty when it has not been
	 * computed yet - no blocking compute happens here.
	 */
	public Optional<Page<T>> cachedPage(int key, Pageable pageable) {
		CachedGroups<T> cached = cache.get(key);

		if (cached == null || !cached.signature().equals(signature.get())) {
			return Optional.empty();
		}

		return Optional.of(paginate(cached.groups(), PageUtils.capped(pageable, maxPageSize.getAsInt())));
	}

	/** An empty page capped to the page-size limit (for a miss/fallback). */
	public Page<T> emptyPage(Pageable pageable) {
		return paginate(List.of(), PageUtils.capped(pageable, maxPageSize.getAsInt()));
	}

	/**
	 * Caches the groups under the signature captured when the compute started, so
	 * the result stays a hit even though the signature may have moved meanwhile.
	 */
	public void put(int key, String signatureAtStart, List<T> groups) {
		cache.put(key, new CachedGroups<>(signatureAtStart, groups));
	}

	public String currentSignature() {
		return signature.get();
	}

	public void invalidate() {
		cache.clear();
	}

	/**
	 * Drops groups that no longer survive {@code retains} from every cached
	 * threshold and refreshes the signature to the current state, so a follow-up
	 * reload shows the pruned result without recomputing.
	 */
	public void evict(Predicate<T> retains) {
		if (cache.isEmpty()) {
			return;
		}

		String current = signature.get();

		cache.replaceAll((_, cached) -> new CachedGroups<>(current, cached.groups().stream().filter(retains).toList()));
	}

	private Page<T> paginate(List<T> all, Pageable pageable) {
		int start = Math.min((int) pageable.getOffset(), all.size());

		int end = Math.min(start + pageable.getPageSize(), all.size());

		return new PageImpl<>(all.subList(start, end), pageable, all.size());
	}
}