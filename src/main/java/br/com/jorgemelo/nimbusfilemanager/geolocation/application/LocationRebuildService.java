package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.Coordinates;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LocationRebuildResult;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationRebuildScope;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.MediaGeoLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.projection.MediaCoordinatesProjection;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationConfidence;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.LocationRebuildProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * Rebuilds resolved locations for already-inventoried media, in keyset-
 * paginated batches (the catalog may hold hundreds of thousands of media). Each
 * batch is resolved in parallel because reverse geocoding is CPU-bound
 * (Point-in-Polygon); the degree of parallelism is bounded so the shared
 * database connection pool is never starved. Manual locations are never
 * touched. Errors are counted per media and never abort the whole rebuild.
 */
@Slf4j
@Service
public class LocationRebuildService {

	private static final int BATCH_SIZE = 500;

	private static final long PROGRESS_LOG_STEP = 10_000;

	private static final List<LocationConfidence> LOW_CONFIDENCES = List.of(LocationConfidence.LOW,
			LocationConfidence.VERY_LOW);

	private final MediaGeoLocationRepository mediaGeoLocationRepository;
	private final MediaLocationService mediaLocationService;
	private final LocationRebuildProperties properties;

	public LocationRebuildService(MediaGeoLocationRepository mediaGeoLocationRepository,
			MediaLocationService mediaLocationService, LocationRebuildProperties properties) {
		this.mediaGeoLocationRepository = mediaGeoLocationRepository;
		this.mediaLocationService = mediaLocationService;
		this.properties = properties;
	}

	long countCandidates(LocationRebuildScope scope) {
		return switch (scope) {
		case PENDING -> mediaGeoLocationRepository.countPending();
		case LOW_CONFIDENCE -> mediaGeoLocationRepository.countByConfidenceForRebuild(LOW_CONFIDENCES);
		case ALL -> mediaGeoLocationRepository.countAllResolvable();
		};
	}

	/**
	 * @param progressListener receives the running number of processed media (used
	 *                         by the admin screen to show progress).
	 */
	public LocationRebuildResult rebuild(LocationRebuildScope scope, LongConsumer progressListener) {
		int parallelism = parallelism();

		log.info("Starting location rebuild. scope={} parallelism={}", scope, parallelism);

		RebuildCounters counters = new RebuildCounters();

		ExecutorService pool = Executors.newFixedThreadPool(parallelism, threadFactory());

		try {
			long lastId = 0;
			long lastLogged = 0;

			while (true) {
				List<Long> ids = nextIds(scope, lastId);

				if (ids.isEmpty()) {
					break;
				}

				lastId = ids.getLast();

				processBatch(pool, ids, counters, progressListener);

				lastLogged = logProgress(scope, counters, lastLogged);
			}
		} finally {
			pool.shutdown();
		}

		log.info("Location rebuild finished. scope={} candidates={} resolved={} unresolved={} errors={}", scope,
				counters.candidates.get(), counters.resolved.get(), counters.unresolved.get(), counters.errors.get());

		return new LocationRebuildResult(scope, counters.candidates.get(), counters.resolved.get(),
				counters.unresolved.get(), counters.errors.get());
	}

	private void processBatch(ExecutorService pool, List<Long> ids, RebuildCounters counters,
			LongConsumer progressListener) {
		List<MediaCoordinatesProjection> coordinates = mediaGeoLocationRepository.findCoordinatesByIds(ids);

		List<Callable<Void>> tasks = new ArrayList<>(coordinates.size());

		for (MediaCoordinatesProjection media : coordinates) {
			tasks.add(() -> {
				processMedia(media, counters, progressListener);

				return null;
			});
		}

		try {
			// invokeAll blocks until every task in the batch has finished; each task
			// isolates its own failure, so no task ever completes exceptionally.
			pool.invokeAll(tasks);
		} catch (InterruptedException _) {
			Thread.currentThread().interrupt();
		}

		// After the batch, report the true cumulative so progress is monotonic and
		// deterministic regardless of the order in which parallel tasks finished.
		if (progressListener != null) {
			progressListener.accept(counters.candidates.get());
		}
	}

	private void processMedia(MediaCoordinatesProjection media, RebuildCounters counters,
			LongConsumer progressListener) {
		long processed = counters.candidates.incrementAndGet();

		try {
			Coordinates coords = Coordinates.of(media.getLatitude(), media.getLongitude());

			if (coords != null && mediaLocationService.resolveAndPersist(media.getId(), coords)) {
				counters.resolved.incrementAndGet();
			} else {
				counters.unresolved.incrementAndGet();
			}
		} catch (Exception e) {
			counters.errors.incrementAndGet();

			log.warn("Error rebuilding location for media {}", media.getId(), e);
		}

		if (progressListener != null) {
			progressListener.accept(processed);
		}
	}

	private long logProgress(LocationRebuildScope scope, RebuildCounters counters, long lastLogged) {
		long processed = counters.candidates.get();

		if (processed - lastLogged < PROGRESS_LOG_STEP) {
			return lastLogged;
		}

		log.info("Location rebuild progress. scope={} processed={} resolved={} unresolved={} errors={}", scope,
				processed, counters.resolved.get(), counters.unresolved.get(), counters.errors.get());

		return processed;
	}

	private int parallelism() {
		return Math.max(1, properties.getParallelism());
	}

	private ThreadFactory threadFactory() {
		AtomicInteger index = new AtomicInteger();

		return runnable -> {
			Thread thread = new Thread(runnable, "location-rebuild-" + index.incrementAndGet());

			thread.setDaemon(true);

			return thread;
		};
	}

	private List<Long> nextIds(LocationRebuildScope scope, long lastId) {
		PageRequest page = PageRequest.of(0, BATCH_SIZE);

		return switch (scope) {
		case PENDING -> mediaGeoLocationRepository.findPendingIds(lastId, page);
		case LOW_CONFIDENCE -> mediaGeoLocationRepository.findIdsByConfidence(LOW_CONFIDENCES, lastId, page);
		case ALL -> mediaGeoLocationRepository.findAllResolvableIds(lastId, page);
		};
	}
}