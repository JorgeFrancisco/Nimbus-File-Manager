package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.Coordinates;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.date.MediaDateResolver;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataDateDifference;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataOptions;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildRequest;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildResponse;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildSimulationResult;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.ResolvedMediaDate;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.extractor.MetadataExtractor;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.model.MetadataRebuildCounters;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.model.MetadataResult;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MetadataRebuildField;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.MediaMetadata;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PageUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class MetadataRebuildService {

	private static final int BATCH_SIZE = 500;

	/**
	 * Bounded retry: the rebuild batch is idempotent (it re-reads every candidate),
	 * so an optimistic-lock conflict with a concurrent flow (e.g. the inventory
	 * watcher) is retried a few times before propagating.
	 */
	private static final int MAX_BATCH_ATTEMPTS = 3;

	/**
	 * How many files a dry run opens. Enough for the sample to be representative of
	 * the folder, small enough that simulating stays a preview instead of costing
	 * what the run itself costs.
	 */
	private static final int PREVIEW_SAMPLE = 50;

	/** How many differences the screen lists; the rest is left to the count. */
	private static final int PREVIEW_ROWS = 10;

	/**
	 * Own REQUIRES_NEW template, mirroring
	 * {@code PhashBacklogService}/{@code InventoryPersistenceService}. Each batch -
	 * and so each retry attempt - runs in a brand-new physical transaction with a
	 * clean persistence context, re-reading the current entity versions. This is a
	 * contract, not an implicit invariant: a future {@code @Transactional} on any
	 * caller can no longer collapse the per-batch isolation nor defeat the retry.
	 */
	private final TransactionTemplate transactionTemplate;

	private final CatalogFileRepository catalogFileRepository;
	private final MetadataExtractor metadataExtractor;
	private final MediaDateResolver mediaDateResolver;
	private final Clock clock;

	public MetadataRebuildService(CatalogFileRepository catalogFileRepository, MetadataExtractor metadataExtractor,
			MediaDateResolver mediaDateResolver, PlatformTransactionManager transactionManager, Clock clock) {
		this.catalogFileRepository = catalogFileRepository;
		this.metadataExtractor = metadataExtractor;
		this.mediaDateResolver = mediaDateResolver;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.clock = clock;
	}

	/**
	 * How many files the request would touch, capped by its own limit. Used by the
	 * settings screen to turn progress into a percentage and an estimate; the
	 * rebuild itself does not need it, since it pages by keyset until exhaustion.
	 */
	public long countCandidates(MetadataRebuildRequest request) {
		String sourcePathText = PathUtils.normalize(request.source());

		String descendantPattern = PathUtils.descendantLikePattern(sourcePathText,
				request.source().getFileSystem().getSeparator());

		long total = catalogFileRepository.countForMetadataRebuild(sourcePathText, descendantPattern,
				request.captureDateNull(), request.dateSource(), request.cutoff());

		return Math.min(total, request.safeLimit());
	}

	public MetadataRebuildResponse rebuild(MetadataRebuildRequest request) {
		return rebuild(request, _ -> {
		}, () -> false);
	}

	/**
	 * Same as {@link #rebuild(MetadataRebuildRequest)} but reports how many files
	 * have been processed to {@code progress} after each batch, and asks
	 * {@code stop} between batches.
	 *
	 * <p>
	 * The stop signal is asked of the caller rather than of a field here, because
	 * the reason to stop lives outside this class and outside this process: a
	 * cancellation the user asked for on another screen, or an inventory that
	 * started meanwhile. A pass that stops keeps every batch it committed - the
	 * work is idempotent, so the next run simply re-reads what is left.
	 */
	public MetadataRebuildResponse rebuild(MetadataRebuildRequest request, LongConsumer progress,
			BooleanSupplier stop) {
		Path sourcePath = request.source();

		String separator = sourcePath.getFileSystem().getSeparator();

		String sourcePathText = PathUtils.normalize(sourcePath);

		String descendantPattern = PathUtils.descendantLikePattern(sourcePathText, separator);

		log.info("Starting metadata rebuild. sourcePath={}, limit={}, batchSize={}", sourcePathText,
				request.safeLimit(), BATCH_SIZE);

		MetadataRebuildCounters counters = new MetadataRebuildCounters();

		long lastId = 0L;

		int remaining = request.safeLimit();

		while (remaining > 0 && !stop.getAsBoolean()) {
			int batchLimit = Math.min(BATCH_SIZE, remaining);

			List<Long> ids = catalogFileRepository.findIdsForMetadataRebuild(sourcePathText, descendantPattern,
					request.captureDateNull(), request.dateSource(), request.cutoff(), lastId,
					PageUtils.firstPage(batchLimit));

			if (ids.isEmpty()) {
				break;
			}

			lastId = ids.getLast();
			remaining -= ids.size();

			// Idempotent batch: retry a bounded number of times on optimistic-lock
			// conflict, counting only the successful attempt (fresh counter each try).
			MetadataRebuildCounters[] batchHolder = new MetadataRebuildCounters[1];

			OptimisticLockRetry.run("metadata rebuild batch", MAX_BATCH_ATTEMPTS, () -> {
				MetadataRebuildCounters batch = new MetadataRebuildCounters();

				processBatch(ids, request, batch);

				batchHolder[0] = batch;
			});

			counters.add(batchHolder[0]);

			progress.accept(counters.processed());
		}

		log.info(
				"Metadata rebuild finished. candidates={} rebuilt={} skippedMissing={} skippedWithoutLocation={} skippedUnsupportedType={} errors={}",
				counters.candidates(), counters.rebuilt(), counters.skippedMissing(), counters.skippedWithoutLocation(),
				counters.skippedUnsupportedType(), counters.errors());

		return new MetadataRebuildResponse(sourcePathText, false, counters.candidates(), counters.rebuilt(),
				counters.skippedMissing(), counters.skippedWithoutLocation(), counters.skippedUnsupportedType(),
				counters.errors(), null);
	}

	/**
	 * A dry run answers three things the bare candidate count never did: how many
	 * files there are, how many the "continue where it stopped" cutoff is hiding,
	 * and - from a sample, because reading every file would cost what the real run
	 * costs - which dates would actually change.
	 */
	public MetadataRebuildSimulationResult simulate(MetadataRebuildRequest request) {
		String sourcePathText = PathUtils.normalize(request.source());

		String descendantPattern = PathUtils.descendantLikePattern(sourcePathText,
				request.source().getFileSystem().getSeparator());

		List<Long> ids = catalogFileRepository.findIdsForMetadataRebuild(sourcePathText, descendantPattern,
				request.captureDateNull(), request.dateSource(), request.cutoff(), 0L,
				PageUtils.firstPage(request.safeLimit()));

		int withoutCutoff = catalogFileRepository.findIdsForMetadataRebuild(sourcePathText, descendantPattern,
				request.captureDateNull(), request.dateSource(), MetadataRebuildRequest.NO_CUTOFF, 0L,
				PageUtils.firstPage(request.safeLimit())).size();

		return sample(ids.stream().limit(PREVIEW_SAMPLE).toList(), request, withoutCutoff - ids.size(), ids.size());
	}

	/** Extracts the sample without writing anything: the entities are only read. */
	private MetadataRebuildSimulationResult sample(List<Long> ids, MetadataRebuildRequest request, int skippedByCutoff,
			int candidates) {
		if (ids.isEmpty()) {
			return new MetadataRebuildSimulationResult(candidates, skippedByCutoff, 0, 0, List.of());
		}

		List<MetadataDateDifference> differences = new ArrayList<>();

		int examined = 0;

		for (CatalogFile catalogFile : catalogFileRepository.findForMetadataRebuildByIds(ids)) {
			Path file = currentPath(catalogFile);

			if (file == null || !Files.exists(file) || !Files.isRegularFile(file)) {
				continue;
			}

			examined++;

			MetadataDateDifference difference = difference(catalogFile, file, request);

			if (difference != null) {
				differences.add(difference);
			}
		}

		return new MetadataRebuildSimulationResult(candidates, skippedByCutoff, examined, differences.size(),
				differences.stream().limit(PREVIEW_ROWS).toList());
	}

	/**
	 * The date this file would end up with, when it differs from the one the
	 * catalog holds today.
	 */
	private MetadataDateDifference difference(CatalogFile catalogFile, Path file, MetadataRebuildRequest request) {
		if (!request.shouldRefresh(MetadataRebuildField.DATE)) {
			return null;
		}

		ResolvedMediaDate resolved;

		try {
			resolved = mediaDateResolver.resolve(metadataExtractor.extract(file, new MetadataOptions(false, true)));
		} catch (RuntimeException e) {
			log.debug("Could not simulate the rebuild of {}", file, e);

			return null;
		}

		MediaMetadata media = catalogFile.getMetadata();

		LocalDateTime current = media == null ? null : media.getCaptureDate();

		if (Objects.equals(current, resolved.captureDate())) {
			return null;
		}

		return new MetadataDateDifference(PathUtils.normalize(file), current,
				media == null ? null : media.getDateSource(), resolved.captureDate(), resolved.dateSource());
	}

	private void processBatch(List<Long> ids, MetadataRebuildRequest request, MetadataRebuildCounters counters) {
		transactionTemplate.executeWithoutResult(_ -> {
			List<CatalogFile> candidates = catalogFileRepository.findForMetadataRebuildByIds(ids);

			log.info("Processing metadata rebuild batch. size={}, firstId={}, lastId={}", ids.size(), ids.getFirst(),
					ids.getLast());

			for (CatalogFile catalogFile : candidates) {
				counters.countProcessed();
				counters.countCandidate();

				Path file = currentPath(catalogFile);

				if (file == null) {
					counters.countSkippedWithoutLocation();

					logProgress(counters, null);
				} else if (!Files.exists(file) || !Files.isRegularFile(file)) {
					counters.countSkippedMissing();

					logProgress(counters, file);
				} else {
					try {
						MetadataResult metadata = metadataExtractor.extract(file, new MetadataOptions(false, true));

						applySelectedFields(catalogFile, metadata, request);

						counters.countRebuilt();
					} catch (Exception e) {
						counters.countError();

						log.warn("Error rebuilding metadata. file={}", file, e);
					}

					logProgress(counters, file);
				}
			}
		});
	}

	private MediaMetadata ensureMedia(CatalogFile catalogFile, MetadataResult metadata) {
		MediaMetadata media = catalogFile.getMetadata();

		if (media == null) {
			media = MediaMetadata.builder().catalogFile(catalogFile)
					.category(FileType.categoryOf(metadata.getFileType())).subcategory(metadata.getSubcategory())
					.build();

			catalogFile.setMetadata(media);
		}

		return media;
	}

	private void applySelectedFields(CatalogFile catalogFile, MetadataResult metadata, MetadataRebuildRequest request) {
		if (request.shouldRefresh(MetadataRebuildField.MIME)) {
			applyFileFields(catalogFile, metadata);
		}

		catalogFile.setLastAnalysis(LocalDateTime.now(clock));

		catalogFile.setAnalysisVersion("1");

		MediaMetadata media = ensureMedia(catalogFile, metadata);

		if (request.shouldRefresh(MetadataRebuildField.DATE)) {
			mediaDateResolver.applyTo(media, metadata);
		}

		boolean isMedia = metadata.getFileType().isMedia();

		if (isMedia && request.shouldRefresh(MetadataRebuildField.GPS)) {
			Coordinates coordinates = Coordinates.of(metadata.getLatitude(), metadata.getLongitude());

			media.setLatitude(coordinates == null ? null : coordinates.latitude());
			media.setLongitude(coordinates == null ? null : coordinates.longitude());
		}

		if (isMedia && request.shouldRefresh(MetadataRebuildField.DIMENSIONS)) {
			media.setStoredWidth(metadata.getStoredWidth());
			media.setStoredHeight(metadata.getStoredHeight());
			media.setDisplayWidth(metadata.getDisplayWidth());
			media.setDisplayHeight(metadata.getDisplayHeight());
			media.setOrientationCode(metadata.getOrientationCode());
			media.setRotation(metadata.getRotation());
			media.setOrientationType(metadata.getOrientationType());
		}

		if (isMedia && request.shouldRefresh(MetadataRebuildField.CAMERA)) {
			media.setManufacturer(metadata.getManufacturer());
			media.setModel(metadata.getModel());
		}

		media.setCategory(FileType.categoryOf(metadata.getFileType()));

		if (request.shouldRefresh(MetadataRebuildField.SUBCATEGORY)) {
			media.setSubcategory(metadata.getSubcategory());
		}

		media.setMetadataJson(metadata.getMetadataJson());
	}

	private void applyFileFields(CatalogFile catalogFile, MetadataResult metadata) {
		catalogFile.setFileName(metadata.getFileName());
		catalogFile.setExtension(metadata.getExtension());
		catalogFile.setSizeBytes(metadata.getSizeBytes());
		catalogFile.setMimeType(metadata.getMimeType());
		catalogFile.setFileType(metadata.getFileType());

		if (MediaProcessingPolicy.isArchiveMasqueradingAsMedia(metadata.getExtension(), metadata.getMimeType())) {
			catalogFile.setSha256(null);
			catalogFile.setMd5(null);
		}

		catalogFile.setCreatedAt(metadata.getCreatedAt());
		catalogFile.setModifiedAt(metadata.getModifiedAt());
		catalogFile.markActive();
		catalogFile.setLastAnalysis(LocalDateTime.now(clock));
		catalogFile.setAnalysisVersion("1");
	}

	private Path currentPath(CatalogFile catalogFile) {
		CatalogFileLocation location = catalogFile.getLocation();

		if (location == null) {
			return null;
		}

		if (location.getCurrentPath() == null || location.getCurrentPath().isBlank()) {
			return null;
		}

		return PathUtils.normalizePath(location.getCurrentPath());
	}

	private void logProgress(MetadataRebuildCounters counters, Path file) {
		if (counters.processed() == 1 || counters.processed() % 1000 == 0) {
			log.info(
					"Metadata rebuild progress: processed={} rebuilt={} skippedMissing={} skippedWithoutLocation={} errors={} currentFile={}",
					counters.processed(), counters.rebuilt(), counters.skippedMissing(),
					counters.skippedWithoutLocation(), counters.errors(), file);
		}
	}
}