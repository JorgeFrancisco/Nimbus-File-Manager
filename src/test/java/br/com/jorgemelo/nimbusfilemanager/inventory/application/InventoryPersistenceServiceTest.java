package br.com.jorgemelo.nimbusfilemanager.inventory.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.dao.DataIntegrityViolationException;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.MediaLocationService;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.mapper.CatalogFileMapper;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.InventoryPersistenceAction;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.ProcessResult;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataOptions;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.model.MetadataResult;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ProcessingCoordinator;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ProcessingMetrics;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.ProcessingProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionPhaseTimings;

@ExtendWith(MockitoExtension.class)
class InventoryPersistenceServiceTest {

	@Mock
	private CatalogFileRepository catalogFileRepository;

	@Mock
	private CatalogFileMapper catalogFileMapper;

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

		when(catalogFileRepository.findByFileKey(fileKey(file))).thenReturn(Optional.of(CatalogFile.builder().build()));

		Assertions.assertThat(service().isCached(file, new MetadataOptions(false, false))).isTrue();
		Assertions.assertThat(service().isCached(file, new MetadataOptions(false, true))).isFalse();
	}

	@Test
	void saveShouldReturnCacheForExistingFileWithoutForceAnalysis() {
		Path file = Path.of("C:/input/photo.jpg");

		when(catalogFileRepository.findByFileKey(fileKey(file))).thenReturn(Optional.of(CatalogFile.builder().build()));

		var result = service().save(file, Path.of("C:/input"), MetadataResult.builder().build(),
				new MetadataOptions(false, false));

		Assertions.assertThat(result).isEqualTo(ProcessResult.CACHE);

		verify(catalogFileMapper, never()).updateEntity(any(), any(), any(), any());
	}

	@Test
	void saveOrCacheShouldReturnCachedWithoutExtractingMetadata() {
		Path file = Path.of("C:/input/photo.jpg");

		AtomicInteger metadataCalls = new AtomicInteger();

		when(catalogFileRepository.findByFileKey(fileKey(file))).thenReturn(Optional.of(CatalogFile.builder().build()));

		var result = service().saveOrCache(file, Path.of("C:/input"), new MetadataOptions(false, false), () -> {
			metadataCalls.incrementAndGet();

			return MetadataResult.builder().build();
		});

		Assertions.assertThat(result.result()).isEqualTo(ProcessResult.CACHE);
		Assertions.assertThat(result.action()).isEqualTo(InventoryPersistenceAction.CACHED);
		Assertions.assertThat(metadataCalls).hasValue(0);

		verify(catalogFileRepository, times(1)).findByFileKey(fileKey(file));
		verify(catalogFileRepository, never()).save(any());
		verify(catalogFileMapper, never()).updateEntity(any(), any(), any(), any());
	}

	@Test
	void saveShouldUpdateExistingFileWhenForceAnalysisIsEnabled() {
		Path file = Path.of("C:/input/photo.jpg");

		CatalogFile existing = CatalogFile.builder().id(1L).build();

		MetadataResult metadata = MetadataResult.builder().build();

		when(catalogFileRepository.findByFileKeyWithDetails(fileKey(file))).thenReturn(Optional.of(existing));

		var result = service().save(file, Path.of("C:/input"), metadata, new MetadataOptions(false, true));

		Assertions.assertThat(result).isEqualTo(ProcessResult.ANALYZED);

		verify(catalogFileRepository, never()).findByFileKey(fileKey(file));
		verify(catalogFileMapper).updateEntity(existing, file, Path.of("C:/input"), metadata);
		verify(catalogFileRepository).save(existing);
	}

	@Test
	void saveShouldCreateNewFileWhenCacheDoesNotExist() {
		Path file = Path.of("C:/input/photo.jpg");

		CatalogFile entity = CatalogFile.builder().id(2L).build();

		MetadataResult metadata = MetadataResult.builder().build();

		when(catalogFileRepository.findByFileKey(fileKey(file))).thenReturn(Optional.empty());
		when(catalogFileMapper.toEntity(file, Path.of("C:/input"), metadata)).thenReturn(entity);

		var result = service().save(file, Path.of("C:/input"), metadata, new MetadataOptions(false, false));

		Assertions.assertThat(result).isEqualTo(ProcessResult.ANALYZED);

		verify(catalogFileRepository).save(entity);
	}

	@Test
	void saveOrCacheBatchShouldReturnEmptyListForEmptyInput() {
		var results = service().saveOrCacheBatch(List.of(), Path.of("C:/input"), new MetadataOptions(false, false),
				_ -> MetadataResult.builder().build());

		Assertions.assertThat(results).isEmpty();

		verify(catalogFileRepository, never()).findExistingFileKeys(any());
		verify(catalogFileRepository, never()).findByFileKeyIn(any());
	}

	@Test
	void saveOrCacheBatchShouldReturnCachedForExistingFilesWithoutExtracting() {
		Path first = Path.of("C:/input/a.jpg");
		Path second = Path.of("C:/input/b.jpg");

		AtomicInteger metadataCalls = new AtomicInteger();

		when(catalogFileRepository.findExistingFileKeys(List.of(fileKey(first), fileKey(second))))
				.thenReturn(List.of(fileKey(first), fileKey(second)));
		when(catalogFileRepository.findByFileKeyIn(List.of(fileKey(first), fileKey(second))))
				.thenReturn(List.of(CatalogFile.builder().fileKey(fileKey(first)).build(),
						CatalogFile.builder().fileKey(fileKey(second)).build()));

		var results = service().saveOrCacheBatch(List.of(first, second), Path.of("C:/input"),
				new MetadataOptions(false, false), _ -> {
					metadataCalls.incrementAndGet();

					return MetadataResult.builder().build();
				});

		Assertions.assertThat(results).hasSize(2);
		Assertions.assertThat(results.get(0).result().result()).isEqualTo(ProcessResult.CACHE);
		Assertions.assertThat(results.get(1).result().result()).isEqualTo(ProcessResult.CACHE);
		Assertions.assertThat(metadataCalls).hasValue(0);

		verify(catalogFileRepository, never()).saveAll(any());
	}

	@Test
	void saveOrCacheBatchShouldCreateNewFilesPreservingInputOrder() {
		Path first = Path.of("C:/input/a.jpg");
		Path second = Path.of("C:/input/b.jpg");

		CatalogFile firstEntity = CatalogFile.builder().id(1L).build();
		CatalogFile secondEntity = CatalogFile.builder().id(2L).build();

		MetadataResult metadata = MetadataResult.builder().build();

		when(catalogFileRepository.findExistingFileKeys(List.of(fileKey(first), fileKey(second))))
				.thenReturn(List.of());
		when(catalogFileRepository.findByFileKeyIn(List.of(fileKey(first), fileKey(second)))).thenReturn(List.of());
		when(catalogFileMapper.toEntity(first, Path.of("C:/input"), metadata)).thenReturn(firstEntity);
		when(catalogFileMapper.toEntity(second, Path.of("C:/input"), metadata)).thenReturn(secondEntity);

		var results = service().saveOrCacheBatch(List.of(first, second), Path.of("C:/input"),
				new MetadataOptions(false, false), _ -> metadata);

		Assertions.assertThat(results).hasSize(2).allSatisfy(item -> {
			Assertions.assertThat(item.result().result()).isEqualTo(ProcessResult.ANALYZED);
			Assertions.assertThat(item.result().action()).isEqualTo(InventoryPersistenceAction.CREATED);
		});

		verify(catalogFileRepository).saveAll(List.of(firstEntity, secondEntity));
	}

	@Test
	void saveOrCacheBatchShouldUpdateExistingFilesWhenForceAnalysisIsEnabled() {
		Path file = Path.of("C:/input/a.jpg");

		CatalogFile existing = CatalogFile.builder().id(1L).fileKey(fileKey(file)).build();

		MetadataResult metadata = MetadataResult.builder().build();

		when(catalogFileRepository.findByFileKeyInWithDetails(List.of(fileKey(file)))).thenReturn(List.of(existing));

		var results = service().saveOrCacheBatch(List.of(file), Path.of("C:/input"), new MetadataOptions(false, true),
				_ -> metadata);

		Assertions.assertThat(results).hasSize(1);
		Assertions.assertThat(results.get(0).result().result()).isEqualTo(ProcessResult.ANALYZED);
		Assertions.assertThat(results.get(0).result().action()).isEqualTo(InventoryPersistenceAction.UPDATED);

		// Force analysis re-analyzes everything, so the cheap existence projection is
		// skipped.
		verify(catalogFileRepository, never()).findExistingFileKeys(any());
		verify(catalogFileRepository, never()).findByFileKeyIn(any());
		verify(catalogFileMapper).updateEntity(existing, file, Path.of("C:/input"), metadata);
		verify(catalogFileRepository).saveAll(List.of(existing));
	}

	@Test
	void saveOrCacheBatchShouldIsolatePerFileMetadataExtractionFailures() {
		Path good = Path.of("C:/input/good.jpg");
		Path bad = Path.of("C:/input/bad.jpg");

		CatalogFile goodEntity = CatalogFile.builder().id(1L).build();

		MetadataResult metadata = MetadataResult.builder().build();

		IllegalStateException failure = new IllegalStateException("bad file");

		when(catalogFileRepository.findExistingFileKeys(List.of(fileKey(good), fileKey(bad)))).thenReturn(List.of());
		when(catalogFileRepository.findByFileKeyIn(List.of(fileKey(good), fileKey(bad)))).thenReturn(List.of());
		when(catalogFileMapper.toEntity(good, Path.of("C:/input"), metadata)).thenReturn(goodEntity);

		var results = service().saveOrCacheBatch(List.of(good, bad), Path.of("C:/input"),
				new MetadataOptions(false, false), file -> {
					if (file.equals(bad)) {
						throw failure;
					}

					return metadata;
				});

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

		when(catalogFileRepository.findExistingFileKeys(any())).thenReturn(List.of());
		when(catalogFileRepository.findByFileKeyIn(any())).thenAnswer(_ -> {
			repositoryThread.set(Thread.currentThread().getName());

			return List.of();
		});
		when(catalogFileMapper.toEntity(file, Path.of("C:/input"), metadata)).thenReturn(entity);

		service().saveOrCacheBatch(List.of(file), Path.of("C:/input"), new MetadataOptions(false, false), _ -> {
			extractorThread.set(Thread.currentThread().getName());

			return metadata;
		});

		// Extraction runs on a pool thread; every JPA call stays off the pool.
		Assertions.assertThat(extractorThread.get()).startsWith("mm-processing-");
		Assertions.assertThat(repositoryThread.get()).doesNotStartWith("mm-processing-");
	}

	@Test
	void saveAllFailureAbortsTheChunk() {
		Path file = Path.of("C:/input/a.jpg");

		MetadataResult metadata = MetadataResult.builder().build();

		CatalogFile entity = CatalogFile.builder().id(1L).build();

		when(catalogFileRepository.findExistingFileKeys(any())).thenReturn(List.of());
		when(catalogFileRepository.findByFileKeyIn(any())).thenReturn(List.of());
		when(catalogFileMapper.toEntity(file, Path.of("C:/input"), metadata)).thenReturn(entity);
		when(catalogFileRepository.saveAll(any())).thenThrow(new DataIntegrityViolationException("duplicate file_key"));

		InventoryPersistenceService service = service();

		var files = List.of(file);

		Path input = Path.of("C:/input");

		MetadataOptions metadataOptions = new MetadataOptions(false, false);

		Assertions.assertThatThrownBy(() -> service.saveOrCacheBatch(files, input, metadataOptions, _ -> metadata))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private InventoryPersistenceService service() {
		if (coordinator == null) {
			// Single worker keeps extraction order deterministic for these unit tests.
			coordinator = new ProcessingCoordinator(new ProcessingProperties(1, 8, 2, 2, 2), new ProcessingMetrics());
		}

		return new InventoryPersistenceService(catalogFileRepository, catalogFileMapper, mediaLocationService,
				coordinator,
				new ResourcelessTransactionManager(), new ProcessingMetrics(), new ExecutionPhaseTimings());
	}

	private String fileKey(Path file) {
		return PathUtils.normalize(file);
	}
}