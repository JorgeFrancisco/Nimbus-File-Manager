package br.com.jorgemelo.nimbusfilemanager.inventory.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.ContentVerificationLauncher;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentSuspect;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.MediaLocationService;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.Coordinates;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.ResolvedCatalogFile;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.ScannedFile;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.InventoryPersistenceAction;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.ProcessResult;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataOptions;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.model.MetadataResult;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ProcessingCoordinator;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.MediaMetadata;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.KnownContentBatchRow;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.ProcessingProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.CatalogLifecycleWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionMetricsContext;

@ExtendWith(MockitoExtension.class)
class InventoryPersistenceServiceTest {

	/** This test's own context: nothing here is shared with another run. */
	private final ExecutionMetricsContext context = new ExecutionMetricsContext();

	@Mock
	private CatalogFileRepository catalogFileRepository;

	@Mock
	private InventoryCatalogResolver inventoryCatalogResolver;

	@Mock
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Mock
	private ContentVerificationLauncher contentVerificationLauncher;

	@Mock
	private CatalogLifecycleWriter catalogLifecycleWriter;

	@Mock
	private MediaLocationService mediaLocationService;

	private ProcessingCoordinator coordinator;

	@AfterEach
	void tearDown() {
		if (coordinator != null) {
			coordinator.shutdown();
		}
	}

	@Test
	void isCachedShouldRespectForceAnalysis() {
		Path file = Path.of("C:/input/photo.jpg");

		when(inventoryCatalogResolver.knows(file)).thenReturn(true);

		Assertions.assertThat(service().isCached(file, new MetadataOptions(false, false))).isTrue();
		Assertions.assertThat(service().isCached(file, new MetadataOptions(false, true))).isFalse();
	}

	@Test
	void saveShouldReturnCacheForExistingFileWithoutForceAnalysis() {
		Path file = Path.of("C:/input/photo.jpg");

		// The entry the path belongs to, which is what a save asks for: whether
		// something is catalogued there at all is the cache check, and another
		// question.
		when(inventoryCatalogResolver.existing(file, false)).thenReturn(Optional.of(CatalogFiles.at(1L, file)));

		var result = service().save(file, MetadataResult.builder().build(),
				new MetadataOptions(false, false));

		Assertions.assertThat(result.result()).isEqualTo(ProcessResult.CACHE);
		Assertions.assertThat(result.action()).isEqualTo(InventoryPersistenceAction.CACHED);

		verify(inventoryCatalogResolver, never()).catalogue(any(), any(), any());
	}

	@Test
	void saveOrCacheShouldReturnCachedWithoutExtractingMetadata() {
		Path file = Path.of("C:/input/photo.jpg");

		AtomicInteger metadataCalls = new AtomicInteger();

		when(inventoryCatalogResolver.existing(file, false)).thenReturn(Optional.of(CatalogFiles.at(1L, file)));

		var result = service().saveOrCache(file, new MetadataOptions(false, false), () -> {
			metadataCalls.incrementAndGet();

			return MetadataResult.builder().build();
		});

		Assertions.assertThat(result.result()).isEqualTo(ProcessResult.CACHE);
		Assertions.assertThat(result.action()).isEqualTo(InventoryPersistenceAction.CACHED);
		Assertions.assertThat(metadataCalls).hasValue(0);

		verify(catalogFileRepository, never()).save(any());
		verify(inventoryCatalogResolver, never()).catalogue(any(), any(), any());
	}

	@Test
	void saveShouldUpdateExistingFileWhenForceAnalysisIsEnabled() {
		Path file = Path.of("C:/input/photo.jpg");

		CatalogFile existing = CatalogFile.builder().id(1L).build();

		MetadataResult metadata = MetadataResult.builder().build();

		when(inventoryCatalogResolver.existing(file, true)).thenReturn(Optional.of(existing));

		catalogues(existing, file);

		var result = service().save(file, metadata, new MetadataOptions(false, true));

		Assertions.assertThat(result.result()).isEqualTo(ProcessResult.ANALYZED);
		Assertions.assertThat(result.action()).as("the entry was active already").isEqualTo(
				InventoryPersistenceAction.UPDATED);

		verify(inventoryCatalogResolver).catalogue(existing, file, metadata);
		verify(catalogFileRepository).save(existing);
	}

	@Test
	void saveShouldCreateNewFileWhenCacheDoesNotExist() {
		Path file = Path.of("C:/input/photo.jpg");

		CatalogFile entity = CatalogFile.builder().id(2L).build();

		MetadataResult metadata = MetadataResult.builder().build();

		when(inventoryCatalogResolver.existing(file, false)).thenReturn(Optional.empty());
		when(inventoryCatalogResolver.catalogue(null, file, metadata))
				.thenReturn(new ResolvedCatalogFile(entity, false, true));

		var result = service().save(file, metadata, new MetadataOptions(false, false));

		Assertions.assertThat(result.result()).isEqualTo(ProcessResult.ANALYZED);
		Assertions.assertThat(result.action()).isEqualTo(InventoryPersistenceAction.CREATED);

		verify(catalogFileRepository).save(entity);
	}

	@Test
	void saveOrCacheBatchShouldReturnEmptyListForEmptyInput() {
		var results = service().saveOrCacheBatch(List.of(), new MetadataOptions(false, false),
				_ -> MetadataResult.builder().build(), context);

		Assertions.assertThat(results).isEmpty();

		verify(inventoryCatalogResolver, never()).present(any());
		verify(inventoryCatalogResolver, never()).existing(anyList(), anyBoolean());
	}

	@Test
	void saveOrCacheBatchShouldReturnCachedForExistingFilesWithoutExtracting() {
		Path first = Path.of("C:/input/a.jpg");
		Path second = Path.of("C:/input/b.jpg");

		AtomicInteger metadataCalls = new AtomicInteger();

		when(inventoryCatalogResolver.present(List.of(first, second)))
				.thenReturn(Set.of(pathKey(first), pathKey(second)));

		var results = service().saveOrCacheBatch(List.of(scanned(first), scanned(second)),
				new MetadataOptions(false, false), _ -> {
					metadataCalls.incrementAndGet();

					return MetadataResult.builder().build();
				}, context);

		Assertions.assertThat(results).hasSize(2);
		Assertions.assertThat(results.get(0).result().result()).isEqualTo(ProcessResult.CACHE);
		Assertions.assertThat(results.get(1).result().result()).isEqualTo(ProcessResult.CACHE);
		Assertions.assertThat(metadataCalls).hasValue(0);

		verify(catalogFileRepository, never()).saveAll(any());

		// No entity is loaded either: a cache hit is catalogued and active, so there is
		// nothing to build. Loading them all cost 33 seconds on an inventory that wrote
		// nothing at all. The one projection the batch does ask for is what tells a
		// cache hit from a file that changed behind the catalog's back.
		verify(inventoryCatalogResolver, never()).existing(anyList(), anyBoolean());
	}

	/**
	 * The other half of the question above: the path is catalogued and present, and
	 * still it is not a cache hit, because what the walk was handed disagrees with
	 * what the catalog holds. Nothing is opened here - deciding whether the bytes
	 * really changed is a verification's job - but the file stops counting as
	 * settled, which is what makes a walk the net under a watcher that missed a
	 * write.
	 */
	@Test
	void aCataloguedFileWhoseSizeMovedIsNotACacheHitAndIsSentForVerification(@TempDir Path folder) {
		Path file = folder.resolve("edited.jpg");

		ScannedFile observed = new ScannedFile(file, 2048L, Instant.EPOCH);

		CatalogFile entity = CatalogFile.builder().id(7L).build();

		MetadataResult metadata = MetadataResult.builder().build();

		when(inventoryCatalogResolver.present(List.of(file))).thenReturn(Set.of(pathKey(file)));
		List<KnownContentBatchRow> known = List.of(knownContent(7L, pathKey(file), 1024L, Instant.EPOCH));

		when(catalogFileLocationRepository.findKnownContentByPaths(any(), any())).thenReturn(known);
		when(inventoryCatalogResolver.existing(anyList(), anyBoolean())).thenReturn(Map.of());
		when(inventoryCatalogResolver.catalogue(null, file, metadata))
				.thenReturn(new ResolvedCatalogFile(entity, false, true));

		var results = service().saveOrCacheBatch(List.of(observed), new MetadataOptions(false, false),
				_ -> metadata, context);

		Assertions.assertThat(results).hasSize(1);
		Assertions.assertThat(results.get(0).result().result()).as("a file whose stat moved is analysed, not skipped")
				.isEqualTo(ProcessResult.ANALYZED);

		ArgumentCaptor<List<ContentSuspect>> asked = ArgumentCaptor.captor();

		// The whole set in one call: this is the batch's only admission, and handing it
		// over at once is what lets the locks behind it be taken in a single order.
		verify(contentVerificationLauncher).verifyAll(asked.capture(), any(), eq(ExecutionTrigger.TIMER));

		Assertions.assertThat(asked.getValue()).containsExactly(new ContentSuspect(7L, pathKey(file)));
	}

	/**
	 * The other end of the same comparison: a file the walk found exactly as the
	 * catalog describes it. Nothing suggests the bytes moved, so it is skipped
	 * without being opened - which is the answer for almost every file of almost
	 * every walk, and the reason a scan of a hundred thousand files is quick.
	 */
	@Test
	void aCataloguedFileTheWalkFoundUnchangedIsSkippedWithoutBeingRead(@TempDir Path folder) {
		Path file = folder.resolve("untouched.jpg");

		ScannedFile observed = new ScannedFile(file, 1024L, Instant.EPOCH);

		List<KnownContentBatchRow> known = List.of(knownContent(7L, pathKey(file), 1024L, Instant.EPOCH));

		when(inventoryCatalogResolver.present(List.of(file))).thenReturn(Set.of(pathKey(file)));
		when(catalogFileLocationRepository.findKnownContentByPaths(any(), any())).thenReturn(known);

		var results = service().saveOrCacheBatch(List.of(observed), new MetadataOptions(false, false),
				_ -> MetadataResult.builder().build(), context);

		Assertions.assertThat(results.get(0).result().result()).isEqualTo(ProcessResult.CACHE);

		verify(contentVerificationLauncher, never()).verify(any(), anyString(), any(), any());
	}

	/**
	 * A file the catalog holds no digest-worthy description of - it was catalogued
	 * before sizes were recorded, or by a pass that did not stat it. There is
	 * nothing to compare, so it is not a cache hit.
	 */
	@Test
	void aCataloguedFileWithNoSizeOnRecordIsNotSomethingAScanMaySkip(@TempDir Path folder) {
		Path file = folder.resolve("undescribed.jpg");

		ScannedFile observed = new ScannedFile(file, 1024L, Instant.EPOCH);

		List<KnownContentBatchRow> known = List.of(knownContent(7L, pathKey(file), null, Instant.EPOCH));

		MetadataResult metadata = MetadataResult.builder().build();

		when(inventoryCatalogResolver.present(List.of(file))).thenReturn(Set.of(pathKey(file)));
		when(catalogFileLocationRepository.findKnownContentByPaths(any(), any())).thenReturn(known);
		when(inventoryCatalogResolver.existing(anyList(), anyBoolean())).thenReturn(Map.of());
		when(inventoryCatalogResolver.catalogue(null, file, metadata))
				.thenReturn(new ResolvedCatalogFile(CatalogFile.builder().id(7L).build(), false, true));

		var results = service().saveOrCacheBatch(List.of(observed), new MetadataOptions(false, false),
				_ -> metadata, context);

		Assertions.assertThat(results.get(0).result().result()).isEqualTo(ProcessResult.ANALYZED);
	}

	@Test
	void saveOrCacheBatchShouldCreateNewFilesPreservingInputOrder() {
		Path first = Path.of("C:/input/a.jpg");
		Path second = Path.of("C:/input/b.jpg");

		CatalogFile firstEntity = CatalogFile.builder().id(1L).build();
		CatalogFile secondEntity = CatalogFile.builder().id(2L).build();

		MetadataResult metadata = MetadataResult.builder().build();

		when(inventoryCatalogResolver.present(anyList())).thenReturn(Set.of());
		when(inventoryCatalogResolver.existing(anyList(), anyBoolean())).thenReturn(Map.of());
		when(inventoryCatalogResolver.catalogue(null, first, metadata))
				.thenReturn(new ResolvedCatalogFile(firstEntity, false, true));
		when(inventoryCatalogResolver.catalogue(null, second, metadata))
				.thenReturn(new ResolvedCatalogFile(secondEntity, false, true));

		var results = service().saveOrCacheBatch(List.of(scanned(first), scanned(second)),
				new MetadataOptions(false, false), _ -> metadata, context);

		Assertions.assertThat(results).hasSize(2).allSatisfy(item -> {
			Assertions.assertThat(item.result().result()).isEqualTo(ProcessResult.ANALYZED);
			Assertions.assertThat(item.result().action()).isEqualTo(InventoryPersistenceAction.CREATED);
		});

		verify(catalogFileRepository).saveAll(List.of(firstEntity, secondEntity));
	}

	@Test
	void saveOrCacheBatchShouldUpdateExistingFilesWhenForceAnalysisIsEnabled() {
		Path file = Path.of("C:/input/a.jpg");

		CatalogFile existing = CatalogFile.builder().id(1L).build();

		MetadataResult metadata = MetadataResult.builder().build();

		when(inventoryCatalogResolver.existing(List.of(file), true)).thenReturn(Map.of(pathKey(file), existing));
		when(inventoryCatalogResolver.catalogue(existing, file, metadata))
				.thenReturn(new ResolvedCatalogFile(existing, false, false));

		var results = service().saveOrCacheBatch(List.of(scanned(file)),
				new MetadataOptions(false, true), _ -> metadata, context);

		Assertions.assertThat(results).hasSize(1);
		Assertions.assertThat(results.get(0).result().result()).isEqualTo(ProcessResult.ANALYZED);
		Assertions.assertThat(results.get(0).result().action()).isEqualTo(InventoryPersistenceAction.UPDATED);

		// Force analysis re-analyzes everything, so the cheap present-check is skipped
		// entirely - there is no point asking which files may be left alone. The entry
		// itself is still loaded, because it is the one being updated.
		verify(inventoryCatalogResolver, never()).present(any());
		verify(inventoryCatalogResolver).catalogue(existing, file, metadata);
		verify(catalogFileRepository).saveAll(List.of(existing));
	}

	/**
	 * The catalog had given up on this file and the walk met it again. Both halves
	 * are asserted together on purpose: the entry comes back and the fact saying so
	 * is written in the same transaction, because a history that ends at "missing"
	 * while the row reads present is the state this was built to make impossible.
	 */
	@Test
	void aFileTheCatalogHadLostIsRecordedAsPresentWhenItIsFoundAgain() {
		Path file = Path.of("C:/input/found-again.jpg");

		CatalogFile lost = CatalogFile.builder().id(9L).build();

		MetadataResult metadata = MetadataResult.builder().build();

		when(inventoryCatalogResolver.present(anyList())).thenReturn(Set.of());
		when(inventoryCatalogResolver.existing(anyList(), anyBoolean())).thenReturn(Map.of(pathKey(file), lost));
		when(inventoryCatalogResolver.catalogue(lost, file, metadata))
				.thenReturn(new ResolvedCatalogFile(lost, true, false));

		var results = service().saveOrCacheBatch(List.of(scanned(file)),
				new MetadataOptions(false, false), _ -> metadata, context);

		Assertions.assertThat(results.get(0).result().action())
				.isEqualTo(InventoryPersistenceAction.REACTIVATED);

		verify(catalogLifecycleWriter).recordPresent(eq(List.of(9L)), any());
	}

	/**
	 * And the file that merely changed does not get one. The fact is about the
	 * catalog finding something it had lost; writing one for every update would
	 * make the timeline unreadable and say nothing.
	 */
	@Test
	void anUpdatedFileThatWasNeverLostRecordsNoReappearance() {
		Path file = Path.of("C:/input/edited.jpg");

		CatalogFile known = CatalogFile.builder().id(4L).build();

		MetadataResult metadata = MetadataResult.builder().build();

		when(inventoryCatalogResolver.present(anyList())).thenReturn(Set.of());
		when(inventoryCatalogResolver.existing(anyList(), anyBoolean())).thenReturn(Map.of(pathKey(file), known));
		when(inventoryCatalogResolver.catalogue(known, file, metadata))
				.thenReturn(new ResolvedCatalogFile(known, false, false));

		service().saveOrCacheBatch(List.of(scanned(file)), new MetadataOptions(false, false),
				_ -> metadata, context);

		verify(catalogLifecycleWriter, never()).recordPresent(any(), any());
	}

	@Test
	void saveOrCacheBatchShouldIsolatePerFileMetadataExtractionFailures() {
		Path good = Path.of("C:/input/good.jpg");
		Path bad = Path.of("C:/input/bad.jpg");

		CatalogFile goodEntity = CatalogFile.builder().id(1L).build();

		MetadataResult metadata = MetadataResult.builder().build();

		IllegalStateException failure = new IllegalStateException("bad file");

		when(inventoryCatalogResolver.present(anyList())).thenReturn(Set.of());
		when(inventoryCatalogResolver.existing(anyList(), anyBoolean())).thenReturn(Map.of());
		when(inventoryCatalogResolver.catalogue(null, good, metadata))
				.thenReturn(new ResolvedCatalogFile(goodEntity, false, true));

		var results = service().saveOrCacheBatch(List.of(scanned(good), scanned(bad)),
				new MetadataOptions(false, false), file -> {
					if (file.equals(bad)) {
						throw failure;
					}

					return metadata;
				}, context);

		Assertions.assertThat(results).hasSize(2);
		Assertions.assertThat(results.get(0).failed()).isFalse();
		Assertions.assertThat(results.get(0).result().result()).isEqualTo(ProcessResult.ANALYZED);
		Assertions.assertThat(results.get(1).failed()).isTrue();
		Assertions.assertThat(results.get(1).exception()).isSameAs(failure);

		verify(catalogFileRepository).saveAll(List.of(goodEntity));
	}

	@Test
	void extractionRunsOnTheExecutorWhilePersistenceStaysOffIt() {
		Path file = Path.of("C:/input/a.jpg");

		MetadataResult metadata = MetadataResult.builder().build();

		CatalogFile entity = CatalogFile.builder().id(1L).build();

		AtomicReference<String> extractorThread = new AtomicReference<>();
		AtomicReference<String> repositoryThread = new AtomicReference<>();

		when(inventoryCatalogResolver.present(anyList())).thenReturn(Set.of());
		// The thread that reads the catalog is the one this test is about.
		when(inventoryCatalogResolver.existing(anyList(), anyBoolean())).thenAnswer(_ -> {
			repositoryThread.set(Thread.currentThread().getName());

			return Map.of();
		});
		when(inventoryCatalogResolver.catalogue(null, file, metadata))
				.thenReturn(new ResolvedCatalogFile(entity, false, true));

		service().saveOrCacheBatch(List.of(scanned(file)), new MetadataOptions(false, false), _ -> {
			extractorThread.set(Thread.currentThread().getName());

			return metadata;
		}, context);

		// Extraction runs on a pool thread; every JPA call stays off the pool.
		Assertions.assertThat(extractorThread.get()).startsWith("mm-processing-");
		Assertions.assertThat(repositoryThread.get()).doesNotStartWith("mm-processing-");
	}

	@Test
	void saveAllFailureAbortsTheChunk() {
		Path file = Path.of("C:/input/a.jpg");

		MetadataResult metadata = MetadataResult.builder().build();

		CatalogFile entity = CatalogFile.builder().id(1L).build();

		when(inventoryCatalogResolver.present(anyList())).thenReturn(Set.of());
		when(inventoryCatalogResolver.existing(anyList(), anyBoolean())).thenReturn(Map.of());
		when(inventoryCatalogResolver.catalogue(null, file, metadata))
				.thenReturn(new ResolvedCatalogFile(entity, false, true));
		when(catalogFileRepository.saveAll(any())).thenThrow(new DataIntegrityViolationException("duplicate file_key"));

		InventoryPersistenceService service = service();

		List<ScannedFile> files = List.of(scanned(file));

		MetadataOptions metadataOptions = new MetadataOptions(false, false);

		Assertions.assertThatThrownBy(
				() -> service.saveOrCacheBatch(files, metadataOptions, _ -> metadata, context))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void savingMediaWithGpsShouldResolveItsLocationWhenTheFeatureIsEnabled() {
		Path file = Path.of("C:/input/photo.jpg");

		CatalogFile existing = withCoordinates(1L, -25.43, -49.27);

		when(inventoryCatalogResolver.existing(file, true)).thenReturn(Optional.of(existing));

		catalogues(existing, file);
		when(mediaLocationService.enabled()).thenReturn(true);

		service().save(file, MetadataResult.builder().build(), new MetadataOptions(false, true));

		verify(mediaLocationService).resolveIfAbsent(1L, new Coordinates(-25.43, -49.27));
	}

	@Test
	void savingMediaShouldSkipLocationResolutionWhenTheFeatureIsDisabled() {
		Path file = Path.of("C:/input/photo.jpg");

		CatalogFile existing = withCoordinates(1L, -25.43, -49.27);

		when(inventoryCatalogResolver.existing(file, true)).thenReturn(Optional.of(existing));

		catalogues(existing, file);
		when(mediaLocationService.enabled()).thenReturn(false);

		service().save(file, MetadataResult.builder().build(), new MetadataOptions(false, true));

		verify(mediaLocationService, never()).resolveIfAbsent(any(), any());
	}

	/**
	 * Only media that actually carries usable GPS is resolved - no metadata, no id
	 * yet, or absent coordinates all mean there is nothing to look up.
	 */
	@Test
	void savingMediaWithoutUsableCoordinatesShouldNotReachTheLocationService() {
		Path file = Path.of("C:/input/photo.jpg");

		CatalogFile withoutMetadata = CatalogFile.builder().id(1L).build();

		when(inventoryCatalogResolver.existing(file, true)).thenReturn(Optional.of(withoutMetadata));
		when(mediaLocationService.enabled()).thenReturn(true);

		catalogues(withoutMetadata, file);

		service().save(file, MetadataResult.builder().build(), new MetadataOptions(false, true));

		CatalogFile withoutGps = CatalogFile.builder().id(2L).metadata(MediaMetadata.builder().build()).build();

		when(inventoryCatalogResolver.existing(file, true)).thenReturn(Optional.of(withoutGps));

		catalogues(withoutGps, file);

		service().save(file, MetadataResult.builder().build(), new MetadataOptions(false, true));

		verify(mediaLocationService, never()).resolveIfAbsent(any(), any());
	}

	/**
	 * Resolving a location is a best-effort enrichment: a failure is logged and
	 * swallowed, because it must never abort an inventory that already persisted.
	 */
	@Test
	void aFailingLocationResolutionShouldNotBreakTheSave() {
		Path file = Path.of("C:/input/photo.jpg");

		CatalogFile existing = withCoordinates(1L, -25.43, -49.27);

		when(inventoryCatalogResolver.existing(file, true)).thenReturn(Optional.of(existing));

		catalogues(existing, file);
		when(mediaLocationService.enabled()).thenReturn(true);
		doThrow(new IllegalStateException("geodata offline")).when(mediaLocationService).resolveIfAbsent(any(), any());

		InventoryPersistenceService service = service();

		Assertions.assertThat(service.save(file, MetadataResult.builder().build(),
				new MetadataOptions(false, true)).result()).isEqualTo(ProcessResult.ANALYZED);

		verify(catalogFileRepository).save(existing);
	}

	@Test
	void aFailingEnabledCheckShouldNotBreakTheSave() {
		Path file = Path.of("C:/input/photo.jpg");

		CatalogFile existing = withCoordinates(1L, -25.43, -49.27);

		when(inventoryCatalogResolver.existing(file, true)).thenReturn(Optional.of(existing));

		catalogues(existing, file);
		when(mediaLocationService.enabled()).thenThrow(new IllegalStateException("settings unavailable"));

		InventoryPersistenceService service = service();

		Assertions.assertThat(service.save(file, MetadataResult.builder().build(),
				new MetadataOptions(false, true)).result()).isEqualTo(ProcessResult.ANALYZED);

		verify(mediaLocationService, never()).resolveIfAbsent(any(), any());
	}

	/**
	 * What the resolver answers about an entry it already knew: the same entity,
	 * neither created nor brought back. Stubbed because the save reads the answer
	 * to decide what it did, and a resolver that answers nothing is not a resolver
	 * the flow can be about.
	 */
	private void catalogues(CatalogFile existing, Path file) {
		when(inventoryCatalogResolver.catalogue(eq(existing), eq(file), any()))
				.thenReturn(new ResolvedCatalogFile(existing, false, false));
	}

	private CatalogFile withCoordinates(Long id, double latitude, double longitude) {
		return CatalogFile.builder().id(id)
				.metadata(MediaMetadata.builder().latitude(latitude).longitude(longitude).build()).build();
	}

	private InventoryPersistenceService service() {
		if (coordinator == null) {
			// Single worker keeps extraction order deterministic for these unit tests.
			coordinator = new ProcessingCoordinator(new ProcessingProperties(1, 8, 2, 2, 2, 1));
		}

		return new InventoryPersistenceService(catalogFileRepository, catalogFileLocationRepository,
				inventoryCatalogResolver, mediaLocationService, contentVerificationLauncher, coordinator,
				new ResourcelessTransactionManager(), catalogLifecycleWriter, Clock.systemUTC());
	}

	/**
	 * A file as the walk hands it over: the path plus the two facts the operating
	 * system had already produced to answer the walk. Every batch here is built
	 * through this, because a batch of bare paths is what the catalog used to
	 * receive and is exactly what stopped it noticing a file edited in place.
	 */
	private ScannedFile scanned(Path file) {
		return new ScannedFile(file, 1024L, Instant.EPOCH);
	}

	private KnownContentBatchRow knownContent(Long catalogFileId, String path, Long sizeBytes, Instant modifiedAt) {
		KnownContentBatchRow row = mock(KnownContentBatchRow.class);

		// Lenient because which of these the pass reads depends on the answer: a row
		// whose stat still matches is never asked what file it belongs to.
		lenient().when(row.getCatalogFileId()).thenReturn(catalogFileId);
		lenient().when(row.getInputPath()).thenReturn(path);
		lenient().when(row.getSizeBytes()).thenReturn(sizeBytes);
		lenient().when(row.getModifiedAt()).thenReturn(modifiedAt);

		return row;
	}

	private String pathKey(Path file) {
		return PathUtils.normalize(file);
	}
}