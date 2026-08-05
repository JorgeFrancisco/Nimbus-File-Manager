package br.com.jorgemelo.nimbusfilemanager.inventory.application;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancelledException;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.MediaLocationService;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.Coordinates;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryBatchItemResult;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryPersistenceResult;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.mapper.CatalogFileMapper;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.InventoryPersistenceAction;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.ProcessResult;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataOptions;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.model.MetadataResult;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ProcessingCoordinator;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ProcessingMetrics;
import br.com.jorgemelo.nimbusfilemanager.processing.application.dto.Outcome;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhaseType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.MediaMetadata;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionPhaseTimings;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class InventoryPersistenceService {

	private static final int EXTRACTION_PROGRESS_STRIDE = 25;

	private final CatalogFileRepository catalogFileRepository;
	private final CatalogFileMapper catalogFileMapper;
	private final MediaLocationService mediaLocationService;
	private final ProcessingCoordinator processingCoordinator;
	private final ProcessingMetrics processingMetrics;
	private final ExecutionPhaseTimings executionPhaseTimings;

	private final TransactionTemplate readTransaction;
	private final TransactionTemplate writeTransaction;

	public InventoryPersistenceService(CatalogFileRepository catalogFileRepository, CatalogFileMapper catalogFileMapper,
			MediaLocationService mediaLocationService, ProcessingCoordinator processingCoordinator,
			PlatformTransactionManager transactionManager, ProcessingMetrics processingMetrics,
			ExecutionPhaseTimings executionPhaseTimings) {
		this.catalogFileRepository = catalogFileRepository;
		this.catalogFileMapper = catalogFileMapper;
		this.mediaLocationService = mediaLocationService;
		this.processingCoordinator = processingCoordinator;
		this.processingMetrics = processingMetrics;
		this.executionPhaseTimings = executionPhaseTimings;

		// Two short, independent transactions (REQUIRES_NEW) so the heavy parallel
		// extraction between them holds no database connection. The Spring Batch chunk
		// transaction that wraps the writer stays *logically* open, but because all SQL
		// here runs inside these REQUIRES_NEW transactions (Hibernate RESOURCE_LOCAL,
		// delayed connection acquisition), no physical connection is *retained* during
		// the extraction phase - only during phases 1 and 3.
		this.readTransaction = new TransactionTemplate(transactionManager);
		this.readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.readTransaction.setReadOnly(true);

		this.writeTransaction = new TransactionTemplate(transactionManager);
		this.writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public boolean isCached(Path file, MetadataOptions options) {
		String fileKey = PathUtils.normalize(file);

		return catalogFileRepository.findByFileKey(fileKey).isPresent() && !options.forceAnalysis();
	}

	/**
	 * Catalogues one file and says what that did to it.
	 *
	 * <p>
	 * The whole result rather than only {@code ProcessResult}, because "written"
	 * and "written, and the entry was brought back from the dead" are the same
	 * answer to the caller's question and very different answers to somebody
	 * else's: a revived entry rejoins the set a duplicate analysis may look at, and
	 * only the operation that called this can say so once for all the files it
	 * catalogued.
	 */
	public InventoryPersistenceResult save(Path file, Path sourcePath, MetadataResult metadata,
			MetadataOptions options) {
		return saveOrCache(file, sourcePath, options, () -> metadata);
	}

	InventoryPersistenceResult saveOrCache(Path file, Path sourcePath, MetadataOptions options,
			Supplier<MetadataResult> metadataSupplier) {
		String fileKey = PathUtils.normalize(file);

		var existingFile = options.forceAnalysis() ? catalogFileRepository.findByFileKeyWithDetails(fileKey)
				: catalogFileRepository.findByFileKey(fileKey);

		return existingFile.map(existing -> {
			if (!options.forceAnalysis()) {
				return new InventoryPersistenceResult(ProcessResult.CACHE, InventoryPersistenceAction.CACHED);
			}

			MetadataResult metadata = metadataSupplier.get();

			boolean reactivated = catalogFileMapper.updateEntity(existing, file, sourcePath, metadata);

			catalogFileRepository.save(existing);

			resolveLocationsQuietly(List.of(existing));

			return new InventoryPersistenceResult(ProcessResult.ANALYZED, actionOf(reactivated));
		}).orElseGet(() -> {
			MetadataResult metadata = metadataSupplier.get();

			CatalogFile entity = catalogFileMapper.toEntity(file, sourcePath, metadata);

			catalogFileRepository.save(entity);

			resolveLocationsQuietly(List.of(entity));

			return new InventoryPersistenceResult(ProcessResult.ANALYZED, InventoryPersistenceAction.CREATED);
		});
	}

	public List<InventoryBatchItemResult> saveOrCacheBatch(List<Path> files, Path sourcePath, MetadataOptions options,
			Function<Path, MetadataResult> metadataExtractor) {
		return saveOrCacheBatch(files, sourcePath, options, metadataExtractor, () -> false);
	}

	/**
	 * Processes one chunk of files in three cleanly separated phases so heavy work
	 * never runs inside a database transaction:
	 * <ol>
	 * <li><b>cache-check</b> - a short read transaction returns which
	 * {@code fileKey}s already exist, so already-catalogued files are not sent to
	 * the pool;</li>
	 * <li><b>parallel extraction</b> - the metadata of the remaining files is
	 * extracted across the {@link ProcessingCoordinator}, off any transaction, so
	 * no connection is held while ffmpeg/ffprobe/IO run;</li>
	 * <li><b>persistence</b> - a short write transaction re-queries the (now
	 * managed) existing rows, builds/updates entities and issues a single
	 * {@code saveAll}.</li>
	 * </ol>
	 * The {@code file_key} UNIQUE constraint is the final guard between the
	 * cache-check and the persistence: if a concurrent writer inserted the same key
	 * in the gap, the {@code saveAll} fails and rolls back the write transaction,
	 * which propagates and fails the chunk - nothing is left half-persisted.
	 * Per-file extraction failures are isolated as error results; only a database
	 * failure aborts the chunk.
	 */
	public List<InventoryBatchItemResult> saveOrCacheBatch(List<Path> files, Path sourcePath, MetadataOptions options,
			Function<Path, MetadataResult> metadataExtractor, BooleanSupplier cancelled) {
		return saveOrCacheBatch(files, sourcePath, options, metadataExtractor, cancelled, _ -> {
		});
	}

	public List<InventoryBatchItemResult> saveOrCacheBatch(List<Path> files, Path sourcePath, MetadataOptions options,
			Function<Path, MetadataResult> metadataExtractor, BooleanSupplier cancelled,
			IntConsumer onExtractionProgress) {
		if (files.isEmpty()) {
			return List.of();
		}

		long wallStart = System.nanoTime();

		long cacheCheckStart = System.nanoTime();

		Set<String> cachedKeys = readTransaction.execute(_ -> existingKeys(files, options));

		executionPhaseTimings.addNanos(ExecutionPhaseType.CACHE_CHECK, System.nanoTime() - cacheCheckStart);

		List<Path> toExtract = new ArrayList<>(files.size());

		for (Path file : files) {
			if (needsWriting(PathUtils.normalize(file), options, cachedKeys)) {
				toExtract.add(file);
			}
		}

		processingMetrics.incCacheAvoided((long) files.size() - toExtract.size());

		long extractionStart = System.nanoTime();

		List<Outcome<Path, MetadataResult>> extracted = extractInSlices(toExtract, cancelled, metadataExtractor,
				onExtractionProgress);

		executionPhaseTimings.addNanos(ExecutionPhaseType.EXTRACTION, System.nanoTime() - extractionStart);
		executionPhaseTimings.addItems(ExecutionPhaseType.EXTRACTION, toExtract.size());

		long persistenceStart = System.nanoTime();

		List<InventoryBatchItemResult> results = writeTransaction
				.execute(_ -> persist(files, sourcePath, options, cachedKeys, extracted));

		executionPhaseTimings.addNanos(ExecutionPhaseType.PERSISTENCE, System.nanoTime() - persistenceStart);
		// The files this step actually wrote. It reported zero before, which made the
		// screen show a long phase with nothing in it.
		executionPhaseTimings.addItems(ExecutionPhaseType.PERSISTENCE, toExtract.size());

		processingMetrics.recordWallClock(System.nanoTime() - wallStart);

		return results;
	}

	/**
	 * Extracts on the shared coordinator in fixed-size slices, reporting the
	 * cumulative count after each slice. The report runs on the caller's thread -
	 * the Spring Batch step thread that owns the transaction context - so the
	 * per-slice {@code updateLiveProgress} can commit and the progress screen
	 * advances mid-chunk. A per-item callback from the coordinator's own worker
	 * threads cannot do this: those threads carry no transaction context, so a
	 * transactional progress update from them silently fails.
	 */
	private List<Outcome<Path, MetadataResult>> extractInSlices(List<Path> toExtract, BooleanSupplier cancelled,
			Function<Path, MetadataResult> metadataExtractor, IntConsumer onExtractionProgress) {
		List<Outcome<Path, MetadataResult>> extracted = new ArrayList<>(toExtract.size());

		for (int start = 0; start < toExtract.size(); start += EXTRACTION_PROGRESS_STRIDE) {
			List<Path> slice = toExtract.subList(start, Math.min(start + EXTRACTION_PROGRESS_STRIDE, toExtract.size()));

			extracted.addAll(processingCoordinator.process(slice, cancelled, metadataExtractor::apply));

			onExtractionProgress.accept(extracted.size());
		}

		return extracted;
	}

	private Set<String> existingKeys(List<Path> files, MetadataOptions options) {
		if (options.forceAnalysis()) {
			return Set.of();
		}

		List<String> keys = files.stream().map(PathUtils::normalize).toList();

		return new HashSet<>(catalogFileRepository.findExistingFileKeys(keys));
	}

	private List<InventoryBatchItemResult> persist(List<Path> files, Path sourcePath, MetadataOptions options,
			Set<String> cachedKeys, List<Outcome<Path, MetadataResult>> extracted) {
		Map<Path, Outcome<Path, MetadataResult>> outcomeByPath = new HashMap<>();

		for (Outcome<Path, MetadataResult> outcome : extracted) {
			outcomeByPath.put(outcome.item(), outcome);
		}

		Map<String, CatalogFile> existingByKey = entitiesToWrite(files, options, cachedKeys);

		List<CatalogFile> toPersist = new ArrayList<>();

		List<InventoryBatchItemResult> results = new ArrayList<>(files.size());

		for (Path file : files) {
			String key = PathUtils.normalize(file);

			CatalogFile existing = existingByKey.get(key);

			if (!needsWriting(key, options, cachedKeys)) {
				results.add(cacheResult(file));
			} else {
				Outcome<Path, MetadataResult> outcome = outcomeByPath.get(file);

				if (outcome == null || outcome.wasCancelled()) {
					results.add(InventoryBatchItemResult.error(file,
							new ExecutionCancelledException("Inventory cancelled by user.")));
				} else if (outcome.failed()) {
					results.add(InventoryBatchItemResult.error(file, outcome.error()));
				} else {
					results.add(persistExtracted(file, sourcePath, existing, outcome.value(), toPersist));
				}
			}
		}

		if (!toPersist.isEmpty()) {
			catalogFileRepository.saveAll(toPersist);

			resolveLocationsQuietly(toPersist);
		}

		return results;
	}

	/**
	 * Whether a file still has to be written. A cache hit is catalogued and active,
	 * so it is neither read nor written - which is the whole point: loading every
	 * entity of every batch cost 33 seconds on an inventory that wrote nothing.
	 */
	private static boolean needsWriting(String key, MetadataOptions options, Set<String> cachedKeys) {
		return options.forceAnalysis() || cachedKeys == null || !cachedKeys.contains(key);
	}

	/** The entities of the files about to be written, and of no others. */
	private Map<String, CatalogFile> entitiesToWrite(List<Path> files, MetadataOptions options,
			Set<String> cachedKeys) {
		List<String> keys = files.stream().map(PathUtils::normalize)
				.filter(key -> needsWriting(key, options, cachedKeys)).toList();

		if (keys.isEmpty()) {
			return Map.of();
		}

		List<CatalogFile> existing = options.forceAnalysis() ? catalogFileRepository.findByFileKeyInWithDetails(keys)
				: catalogFileRepository.findByFileKeyIn(keys);

		return existing.stream().collect(Collectors.toMap(CatalogFile::getFileKey, Function.identity()));
	}

	private InventoryBatchItemResult persistExtracted(Path file, Path sourcePath, CatalogFile existing,
			MetadataResult metadata, List<CatalogFile> toPersist) {
		if (existing != null) {
			boolean reactivated = catalogFileMapper.updateEntity(existing, file, sourcePath, metadata);

			toPersist.add(existing);

			return InventoryBatchItemResult.of(file,
					new InventoryPersistenceResult(ProcessResult.ANALYZED, actionOf(reactivated)));
		}

		CatalogFile entity = catalogFileMapper.toEntity(file, sourcePath, metadata);

		toPersist.add(entity);

		return InventoryBatchItemResult.of(file,
				new InventoryPersistenceResult(ProcessResult.ANALYZED, InventoryPersistenceAction.CREATED));
	}

	/**
	 * Both kinds of update, told apart by whether the entry was set aside before
	 * it. The distinction is not cosmetic: only one of the two changes which files
	 * the rest of the product may look at, and the pass reports it once at the end.
	 */
	private static InventoryPersistenceAction actionOf(boolean reactivated) {
		return reactivated ? InventoryPersistenceAction.REACTIVATED : InventoryPersistenceAction.UPDATED;
	}

	private InventoryBatchItemResult cacheResult(Path file) {
		return InventoryBatchItemResult.of(file,
				new InventoryPersistenceResult(ProcessResult.CACHE, InventoryPersistenceAction.CACHED));
	}

	/**
	 * Offline location resolution hook: when the feature is enabled, resolves
	 * country/state/city for every just-persisted media that carries GPS and has no
	 * location yet. Failures are logged and swallowed - resolving a location must
	 * never interrupt the inventory.
	 */
	private void resolveLocationsQuietly(List<CatalogFile> persisted) {
		try {
			if (!mediaLocationService.enabled()) {
				return;
			}

			for (CatalogFile catalogFile : persisted) {
				resolveLocationQuietly(catalogFile);
			}
		} catch (Exception e) {
			log.warn("Location resolution skipped for this batch", e);
		}
	}

	private void resolveLocationQuietly(CatalogFile catalogFile) {
		try {
			MediaMetadata media = catalogFile.getMetadata();

			if (media == null || catalogFile.getId() == null) {
				return;
			}

			Coordinates coordinates = Coordinates.of(media.getLatitude(), media.getLongitude());

			if (coordinates != null) {
				mediaLocationService.resolveIfAbsent(catalogFile.getId(), coordinates);
			}
		} catch (Exception e) {
			log.warn("Could not resolve location for media {} during inventory", catalogFile.getId(), e);
		}
	}
}