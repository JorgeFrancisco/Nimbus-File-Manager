package br.com.jorgemelo.nimbusfilemanager.inventory.application.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.IntConsumer;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.Chunk;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.InventoryPersistenceService;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryBatchItemResult;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryPersistenceResult;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.InventoryPersistenceAction;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.ProcessResult;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.facade.MetadataFacade;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

@ExtendWith(MockitoExtension.class)
class InventoryItemWriterTest {

	@Mock
	private InventoryPersistenceService inventoryPersistenceService;

	@Mock
	private MetadataFacade metadataFacade;

	@Mock
	private ExecutionErrorService executionErrorService;

	@Mock
	private ExecutionProgressService executionProgressService;

	@Mock
	private ExecutionRepository executionRepository;

	@Mock
	private ExecutionCancellationService executionCancellationService;

	@Test
	void shouldPersistChunkAndReportAccumulatedProgress() {
		Execution execution = Execution.builder().id(5L).build();

		Path analyzed = Path.of("C:/media/new.jpg");
		Path cached = Path.of("C:/media/cached.jpg");
		Path failed = Path.of("C:/media/broken.jpg");

		Exception boom = new IllegalStateException("boom");

		when(executionRepository.findById(5L)).thenReturn(Optional.of(execution));
		when(inventoryPersistenceService.saveOrCacheBatch(anyList(), any(), any(), any(), any(), any()))
				.thenReturn(List.of(
						InventoryBatchItemResult.of(analyzed,
								new InventoryPersistenceResult(ProcessResult.ANALYZED,
										InventoryPersistenceAction.CREATED)),
						InventoryBatchItemResult.of(cached,
								new InventoryPersistenceResult(ProcessResult.CACHE, InventoryPersistenceAction.CACHED)),
						InventoryBatchItemResult.error(failed, boom)));

		InventoryItemWriter writer = writer();

		writer.write(new Chunk<>(List.of(analyzed, cached, failed)));

		verify(executionErrorService).save(failed, boom, execution);
		verify(executionProgressService).updateProgress(execution, 3, 1, 1, 1, failed);
	}

	@Test
	void shouldAccumulateCountsAcrossMultipleChunks() {
		Execution execution = Execution.builder().id(5L).build();

		Path first = Path.of("C:/media/a.jpg");
		Path second = Path.of("C:/media/b.jpg");

		when(executionRepository.findById(5L)).thenReturn(Optional.of(execution));
		when(inventoryPersistenceService.saveOrCacheBatch(eq(List.of(first)), any(), any(), any(), any(), any()))
				.thenReturn(List.of(InventoryBatchItemResult.of(first,
						new InventoryPersistenceResult(ProcessResult.ANALYZED, InventoryPersistenceAction.CREATED))));
		when(inventoryPersistenceService.saveOrCacheBatch(eq(List.of(second)), any(), any(), any(), any(), any()))
				.thenReturn(List.of(InventoryBatchItemResult.of(second,
						new InventoryPersistenceResult(ProcessResult.CACHE, InventoryPersistenceAction.CACHED))));

		InventoryItemWriter writer = writer();

		writer.write(new Chunk<>(List.of(first)));
		writer.write(new Chunk<>(List.of(second)));

		verify(executionProgressService).updateProgress(execution, 1, 1, 0, 0, first);
		verify(executionProgressService).updateProgress(execution, 2, 1, 1, 0, second);
	}

	@Test
	void reportsGranularLiveProgressWhileTheChunkIsStillExtracting() {
		Execution execution = Execution.builder().id(5L).build();

		Path file = Path.of("C:/media/a.jpg");

		when(executionRepository.findById(5L)).thenReturn(Optional.of(execution));
		when(inventoryPersistenceService.saveOrCacheBatch(anyList(), any(), any(), any(), any(), any()))
				.thenAnswer(invocation -> {
					IntConsumer onExtractionProgress = invocation.getArgument(5);
					onExtractionProgress.accept(25);
					onExtractionProgress.accept(50);

					return List.of();
				});

		InventoryItemWriter writer = writer();

		writer.write(new Chunk<>(List.of(file)));

		verify(executionProgressService).updateLiveProgress(execution, 25, 0, 0, 0,
				ExecutionMessages.extractingMetadata());
		verify(executionProgressService).updateLiveProgress(execution, 50, 0, 0, 0,
				ExecutionMessages.extractingMetadata());
	}

	@Test
	void writeShouldThrowWhenExecutionNoLongerExists() {
		when(executionRepository.findById(5L)).thenReturn(Optional.empty());

		InventoryItemWriter writer = writer();

		Chunk<Path> chunk = new Chunk<>(List.of(Path.of("C:/media/a.jpg")));

		Assertions.assertThatThrownBy(() -> writer.write(chunk)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Execution not found: 5");
	}

	private InventoryItemWriter writer() {
		InventoryItemWriterParameters parameters = new InventoryItemWriterParameters("C:/media", "true", "false", 5L);

		return new InventoryItemWriter(inventoryPersistenceService, metadataFacade, executionErrorService,
				executionProgressService, executionRepository, executionCancellationService, parameters);
	}
}