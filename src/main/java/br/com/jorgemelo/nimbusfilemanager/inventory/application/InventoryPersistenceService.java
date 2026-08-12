package br.com.jorgemelo.nimbusfilemanager.inventory.application;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

import br.com.jorgemelo.nimbusfilemanager.catalog.application.ContentVerificationLauncher;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentSuspect;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancelledException;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.MediaLocationService;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.Coordinates;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.CacheCheck;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryBatchItemResult;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryPersistenceResult;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.ScannedFile;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.InventoryPersistenceAction;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.ProcessResult;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataOptions;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.model.MetadataResult;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ProcessingCoordinator;
import br.com.jorgemelo.nimbusfilemanager.processing.application.dto.Outcome;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.CatalogFactProvenance;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhaseType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.MediaMetadata;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.KnownContentBatchRow;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.CatalogLifecycleWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionMetricsContext;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionPhaseTimings;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ProcessingMetrics;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class InventoryPersistenceService {

	private static final int EXTRACTION_PROGRESS_STRIDE = 25;

	private final CatalogFileRepository catalogFileRepository;
	private final CatalogFileLocationRepository catalogFileLocationRepository;
	private final ContentVerificationLauncher contentVerificationLauncher;
	private final CatalogLifecycleWriter catalogLifecycleWriter;
	private final Clock clock;
	private final InventoryCatalogResolver inventoryCatalogResolver;
	private final MediaLocationService mediaLocationService;
	private final ProcessingCoordinator processingCoordinator;

	private final TransactionTemplate readTransaction;
	private final TransactionTemplate writeTransaction;

	public InventoryPersistenceService(CatalogFileRepository catalogFileRepository,
			CatalogFileLocationRepository catalogFileLocationRepository,
			InventoryCatalogResolver inventoryCatalogResolver, MediaLocationService mediaLocationService,
			ContentVerificationLauncher contentVerificationLauncher, ProcessingCoordinator processingCoordinator,
			PlatformTransactionManager transactionManager, CatalogLifecycleWriter catalogLifecycleWriter,
			Clock clock) {
		this.catalogFileRepository = catalogFileRepository;
		this.catalogFileLocationRepository = catalogFileLocationRepository;
		this.contentVerificationLauncher = contentVerificationLauncher;
		this.catalogLifecycleWriter = catalogLifecycleWriter;
		this.clock = clock;
		this.inventoryCatalogResolver = inventoryCatalogResolver;
		this.mediaLocationService = mediaLocationService;
		this.processingCoordinator = processingCoordinator;

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

	/**
	 * Whether this path has been analysed before, which is the question a scan asks
	 * to decide whether to open the file at all. Any file remembered at the path
	 * answers it - present or missing - because the analysis it is being asked
	 * about is on record either way.
	 */
	public boolean isCached(Path file, MetadataOptions options) {
		return inventoryCatalogResolver.knows(file) && !options.forceAnalysis();
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
	public InventoryPersistenceResult save(Path file, MetadataResult metadata, MetadataOptions options) {
		return saveOrCache(file, options, () -> metadata);
	}

	InventoryPersistenceResult saveOrCache(Path file, MetadataOptions options,
			Supplier<MetadataResult> metadataSupplier) {
		var existingFile = inventoryCatalogResolver.existing(file, options.forceAnalysis());

		return existingFile.map(existing -> {
			if (!options.forceAnalysis()) {
				return new InventoryPersistenceResult(ProcessResult.CACHE, InventoryPersistenceAction.CACHED);
			}

			MetadataResult metadata = metadataSupplier.get();

			boolean reactivated = inventoryCatalogResolver.catalogue(existing, file, metadata).reactivated();

			writeTransaction.executeWithoutResult(_ -> {
				catalogFileRepository.save(existing);

				placeNewlyCatalogued(List.of(existing));
			});

			resolveLocationsQuietly(List.of(existing));

			recordReappearance(reactivated ? List.of(existing) : List.of());

			return new InventoryPersistenceResult(ProcessResult.ANALYZED, actionOf(reactivated));
		}).orElseGet(() -> {
			MetadataResult metadata = metadataSupplier.get();

			CatalogFile entity = inventoryCatalogResolver.catalogue(null, file, metadata).entity();

			// The file and where it was found are one write: a placement takes its
			// identity from the file it places, so the two cannot be in separate
			// transactions - the second would be asked to name a file it can no longer
			// reach. The batch path below has said this all along; this one had nothing
			// to say it with while the aggregate carried the placement for it.
			writeTransaction.executeWithoutResult(_ -> {
				catalogFileRepository.save(entity);

				placeNewlyCatalogued(List.of(entity));
			});

			resolveLocationsQuietly(List.of(entity));

			return new InventoryPersistenceResult(ProcessResult.ANALYZED, InventoryPersistenceAction.CREATED);
		});
	}

	public List<InventoryBatchItemResult> saveOrCacheBatch(List<ScannedFile> scanned,
			MetadataOptions options, Function<Path, MetadataResult> metadataExtractor,
			ExecutionMetricsContext metricsContext) {
		return saveOrCacheBatch(scanned, options, metadataExtractor, metricsContext, () -> false);
	}

	/**
	 * Processes one chunk of files in three cleanly separated phases so heavy work
	 * never runs inside a database transaction:
	 * <ol>
	 * <li><b>cache-check</b> - a short read transaction returns which
	 * placements already exist, so already-catalogued files are not sent to
	 * the pool;</li>
	 * <li><b>parallel extraction</b> - the metadata of the remaining files is
	 * extracted across the {@link ProcessingCoordinator}, off any transaction, so
	 * no connection is held while ffmpeg/ffprobe/IO run;</li>
	 * <li><b>persistence</b> - a short write transaction re-queries the (now
	 * managed) existing rows, builds/updates entities and issues a single
	 * {@code saveAll}.</li>
	 * </ol>
	 * The unique canonical placement is the final guard between the cache-check
	 * and the persistence: if a concurrent writer catalogued the same path in the
	 * gap, the {@code saveAll} fails and rolls back the write transaction,
	 * which propagates and fails the chunk - nothing is left half-persisted.
	 * Per-file extraction failures are isolated as error results; only a database
	 * failure aborts the chunk.
	 */
	public List<InventoryBatchItemResult> saveOrCacheBatch(List<ScannedFile> scanned,
			MetadataOptions options, Function<Path, MetadataResult> metadataExtractor,
			ExecutionMetricsContext metricsContext, BooleanSupplier cancelled) {
		return saveOrCacheBatch(scanned, options, metadataExtractor, metricsContext, cancelled, _ -> {
		});
	}

	public List<InventoryBatchItemResult> saveOrCacheBatch(List<ScannedFile> scanned,
			MetadataOptions options, Function<Path, MetadataResult> metadataExtractor,
			ExecutionMetricsContext metricsContext, BooleanSupplier cancelled, IntConsumer onExtractionProgress) {
		ProcessingMetrics processingMetrics = metricsContext.processing();

		ExecutionPhaseTimings executionPhaseTimings = metricsContext.phases();

		if (scanned.isEmpty()) {
			return List.of();
		}

		List<Path> files = scanned.stream().map(ScannedFile::path).toList();

		long wallStart = System.nanoTime();

		long cacheCheckStart = System.nanoTime();

		// The template declares a nullable return; this callback never returns null, and
		// saying so here is what keeps the reader below from being a possible dereference.
		CacheCheck cacheCheck = Objects
				.requireNonNull(readTransaction.execute(_ -> existingKeys(scanned, files, options)));

		Set<String> cachedKeys = cacheCheck.settledKeys();

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
				processingMetrics, onExtractionProgress);

		executionPhaseTimings.addNanos(ExecutionPhaseType.EXTRACTION, System.nanoTime() - extractionStart);
		executionPhaseTimings.addItems(ExecutionPhaseType.EXTRACTION, toExtract.size());

		long persistenceStart = System.nanoTime();

		// The verifications go in with the batch, not before and not after. Before,
		// they were asked for inside the read-only cache check and PostgreSQL refused
		// the insert; committed separately, a batch that then rolled back would leave
		// behind requests about a state nothing confirmed. This is the same boundary
		// the reconcile already uses for the identical decision.
		List<InventoryBatchItemResult> results = writeTransaction.execute(_ -> {
			requestVerification(cacheCheck.suspects());

			return persist(files, options, cachedKeys, extracted);
		});

		executionPhaseTimings.addNanos(ExecutionPhaseType.PERSISTENCE, System.nanoTime() - persistenceStart);
		// The files this step actually wrote. It reported zero before, which made the
		// screen show a long phase with nothing in it.
		executionPhaseTimings.addItems(ExecutionPhaseType.PERSISTENCE, toExtract.size());

		processingMetrics.recordWallClock(System.nanoTime() - wallStart);

		return results;
	}

	/**
	 * Asks for the content of each suspect to be verified, from inside the write
	 * that records the batch.
	 *
	 * <p>
	 * The instant is the batch's own and honestly so: a walk has no timestamp from
	 * the operating system behind it, and when it looked is the truest thing that
	 * can be said about when the difference was noticed. One instant for the batch
	 * rather than one per file, because the batch is what was observed.
	 *
	 * <p>
	 * Handed over as a set rather than one at a time: this is the only admission
	 * this transaction makes, and admitting the whole set at once is what lets the
	 * locks behind it be taken in one order.
	 */
	private void requestVerification(List<ContentSuspect> suspects) {
		contentVerificationLauncher.verifyAll(suspects, Instant.now(clock), ExecutionTrigger.TIMER);
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
			Function<Path, MetadataResult> metadataExtractor, ProcessingMetrics processingMetrics,
			IntConsumer onExtractionProgress) {
		List<Outcome<Path, MetadataResult>> extracted = new ArrayList<>(toExtract.size());

		for (int start = 0; start < toExtract.size(); start += EXTRACTION_PROGRESS_STRIDE) {
			List<Path> slice = toExtract.subList(start, Math.min(start + EXTRACTION_PROGRESS_STRIDE, toExtract.size()));

			extracted.addAll(
					processingCoordinator.process(slice, cancelled, metadataExtractor::apply, processingMetrics));

			onExtractionProgress.accept(extracted.size());
		}

		return extracted;
	}

	/**
	 * The files this batch does not have to open, which is not the same as the
	 * files it has already catalogued.
	 *
	 * <p>
	 * It used to be: a path the catalog knew was skipped, and nothing compared what
	 * was on disk against what had been recorded. A file edited in place was
	 * therefore invisible to every scan for as long as it kept its name - the walk
	 * was handed its size and its timestamp by the operating system and threw them
	 * away before anyone could ask.
	 *
	 * <p>
	 * Now they are asked. A catalogued file whose size and modification time still
	 * match is a cache hit exactly as before, at the cost of one query per batch
	 * and no reading at all. One whose stat has moved is not: it does not get
	 * opened here either - that is a durable verification's job - but it stops
	 * counting as settled, which is what makes a walk the net under a watcher that
	 * missed something.
	 */
	private CacheCheck existingKeys(List<ScannedFile> scanned, List<Path> files, MetadataOptions options) {
		if (options.forceAnalysis()) {
			return CacheCheck.nothingSettled();
		}

		Set<String> present = inventoryCatalogResolver.present(files);

		if (present.isEmpty()) {
			return CacheCheck.nothingSettled();
		}

		return settled(scanned, present);
	}

	private CacheCheck settled(List<ScannedFile> scanned, Set<String> present) {
		Map<String, ScannedFile> byKey = scanned.stream()
				.collect(Collectors.toMap(file -> PathUtils.normalize(file.path()), file -> file, (a, _) -> a));

		String[] paths = present.toArray(String[]::new);

		Set<String> suspects = new HashSet<>();

		List<ContentSuspect> toVerify = new ArrayList<>();

		for (KnownContentBatchRow known : catalogFileLocationRepository.findKnownContentByPaths(paths,
				PathFlavor.of(Path.of(paths[0])).name())) {
			ScannedFile observed = byKey.get(known.getInputPath());

			if (observed == null || matches(known, observed)) {
				continue;
			}

			suspects.add(known.getInputPath());

			// Carried out as data rather than requested here: this runs inside the
			// read-only transaction of the cache check, and asking for a verification is
			// an insert. The caller makes the request from the batch's write.
			toVerify.add(new ContentSuspect(known.getCatalogFileId(), known.getInputPath()));
		}

		if (suspects.isEmpty()) {
			return new CacheCheck(present, List.of());
		}

		log.info("{} catalogued file(s) in this batch no longer match the size or timestamp on record; their "
				+ "content will be verified", suspects.size());

		return new CacheCheck(present.stream().filter(key -> !suspects.contains(key)).collect(Collectors.toSet()),
				toVerify);
	}

	/**
	 * Size and modification time only. Neither proves the bytes are the same - that
	 * is the digest's job, and paying for one here would mean reading every file of
	 * every scan. What they can do is say when nothing suggests otherwise, which is
	 * the answer for almost every file of almost every walk.
	 */
	private static boolean matches(KnownContentBatchRow known, ScannedFile observed) {
		return known.getSizeBytes() != null && known.getSizeBytes() == observed.sizeBytes()
				&& observed.modifiedAt().equals(known.getModifiedAt());
	}

	private List<InventoryBatchItemResult> persist(List<Path> files, MetadataOptions options,
			Set<String> cachedKeys, List<Outcome<Path, MetadataResult>> extracted) {
		Map<Path, Outcome<Path, MetadataResult>> outcomeByPath = new HashMap<>();

		for (Outcome<Path, MetadataResult> outcome : extracted) {
			outcomeByPath.put(outcome.item(), outcome);
		}

		Map<String, CatalogFile> existingByKey = entitiesToWrite(files, options, cachedKeys);

		List<CatalogFile> toPersist = new ArrayList<>();
		List<CatalogFile> reappeared = new ArrayList<>();

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
					results.add(persistExtracted(file, existing, outcome.value(), toPersist, reappeared));
				}
			}
		}

		if (!toPersist.isEmpty()) {
			catalogFileRepository.saveAll(toPersist);

			placeNewlyCatalogued(toPersist);

			recordReappearance(reappeared);

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
		List<Path> wanted = files.stream().filter(file -> needsWriting(PathUtils.normalize(file), options, cachedKeys))
				.toList();

		if (wanted.isEmpty()) {
			return Map.of();
		}

		return inventoryCatalogResolver.existing(wanted, options.forceAnalysis());
	}

	private InventoryBatchItemResult persistExtracted(Path file, CatalogFile existing,
			MetadataResult metadata, List<CatalogFile> toPersist, List<CatalogFile> reappeared) {
		if (existing != null) {
			boolean reactivated = inventoryCatalogResolver.catalogue(existing, file, metadata).reactivated();

			toPersist.add(existing);

			if (reactivated) {
				reappeared.add(existing);
			}

			return InventoryBatchItemResult.of(file,
					new InventoryPersistenceResult(ProcessResult.ANALYZED, actionOf(reactivated)));
		}

		CatalogFile entity = inventoryCatalogResolver.catalogue(null, file, metadata).entity();

		toPersist.add(entity);

		return InventoryBatchItemResult.of(file,
				new InventoryPersistenceResult(ProcessResult.ANALYZED, InventoryPersistenceAction.CREATED));
	}

	/**
	 * A file the catalog had lost and has just met again, said out loud.
	 *
	 * <p>
	 * Written here rather than where the entry is promoted, because this is the
	 * transaction the promotion commits in and the two have to arrive together. The
	 * evidence is exactly what a walk establishes and no more: there was a file at
	 * the place the catalog expected one.
	 */
	private void recordReappearance(List<CatalogFile> reappeared) {
		if (reappeared.isEmpty()) {
			return;
		}

		catalogLifecycleWriter.recordPresent(reappeared.stream().map(CatalogFile::getId).toList(),
				new CatalogFactProvenance(Instant.now(clock), CatalogEventSources.INVENTORY,
						CatalogEventEvidence.PATH_FOUND, null));
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
	 * Where a file was found, written down once, by the pass that catalogued it.
	 *
	 * <p>
	 * The aggregate does not carry its placement to the database - saving a file is
	 * not how a file moves - so the first placement is persisted here, beside the
	 * insert it belongs to and inside the same transaction. Everything after this
	 * is a relocation, which belongs to the doors that record a fact and refuse an
	 * occupied destination.
	 *
	 * <p>
	 * A placement already on record has its identity, which is the file's, so this
	 * asks for exactly the ones that have never been written. Batched in one call
	 * for the same reason the files above are: a library is catalogued in
	 * thousands.
	 */
	private void placeNewlyCatalogued(List<CatalogFile> catalogued) {
		List<CatalogFileLocation> placements = catalogued.stream().map(CatalogFile::getLocation)
				.filter(placement -> placement != null && placement.getId() == null).toList();

		if (!placements.isEmpty()) {
			catalogFileLocationRepository.saveAll(placements);
		}
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