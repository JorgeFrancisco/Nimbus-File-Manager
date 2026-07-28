package br.com.jorgemelo.nimbusfilemanager.inventory.application.batch;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.InventoryPersistenceService;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryBatchItemResult;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataOptions;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.facade.MetadataFacade;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * Persists each chunk emitted by {@link InventoryFileItemReader} through the
 * same {@link InventoryPersistenceService#saveOrCacheBatch} used before this
 * rewrite: one batched existence-check query plus one {@code saveAll} per
 * chunk, instead of one round-trip per file. Running totals live on this
 * {@code @StepScope} bean (a fresh instance per step execution), then get
 * flushed onto the {@link Execution} row after every chunk so the existing
 * progress screen keeps working unchanged.
 */
@Component
@StepScope
public class InventoryItemWriter implements ItemWriter<Path> {

	private final InventoryPersistenceService inventoryPersistenceService;
	private final MetadataFacade metadataFacade;
	private final ExecutionErrorService executionErrorService;
	private final ExecutionProgressService executionProgressService;
	private final ExecutionRepository executionRepository;
	private final ExecutionCancellationService executionCancellationService;

	private final Path sourcePath;
	private final MetadataOptions metadataOptions;
	private final Long executionId;

	private final AtomicInteger found = new AtomicInteger();
	private final AtomicInteger analyzed = new AtomicInteger();
	private final AtomicInteger cacheHits = new AtomicInteger();
	private final AtomicInteger errors = new AtomicInteger();

	public InventoryItemWriter(InventoryPersistenceService inventoryPersistenceService, MetadataFacade metadataFacade,
			ExecutionErrorService executionErrorService, ExecutionProgressService executionProgressService,
			ExecutionRepository executionRepository, ExecutionCancellationService executionCancellationService,
			InventoryItemWriterParameters parameters) {
		this.inventoryPersistenceService = inventoryPersistenceService;
		this.metadataFacade = metadataFacade;
		this.executionErrorService = executionErrorService;
		this.executionProgressService = executionProgressService;
		this.executionRepository = executionRepository;
		this.executionCancellationService = executionCancellationService;
		this.sourcePath = parameters.sourcePath();
		this.metadataOptions = parameters.metadataOptions();
		this.executionId = parameters.executionId();
	}

	@Override
	public void write(Chunk<? extends Path> chunk) {
		Execution execution = findExecution();

		List<Path> files = List.copyOf(chunk.getItems());

		int baseFound = found.get();

		found.addAndGet(files.size());

		IntConsumer onExtractionProgress = done -> executionProgressService.updateLiveProgress(execution,
				baseFound + done, analyzed.get(), cacheHits.get(), errors.get(),
				ExecutionMessages.extractingMetadata());

		List<InventoryBatchItemResult> results = inventoryPersistenceService.saveOrCacheBatch(files, sourcePath,
				metadataOptions, file -> metadataFacade.extract(file, metadataOptions),
				() -> executionCancellationService.isCancelled(executionId), onExtractionProgress);

		Path lastFile = null;

		for (InventoryBatchItemResult result : results) {
			lastFile = result.file();

			if (result.failed()) {
				executionErrorService.save(result.file(), result.exception(), execution);

				errors.incrementAndGet();

				continue;
			}

			switch (result.result().result()) {
			case ANALYZED -> analyzed.incrementAndGet();
			case CACHE -> cacheHits.incrementAndGet();
			case ERROR -> errors.incrementAndGet();
			}
		}

		executionProgressService.updateProgress(execution, found.get(), analyzed.get(), cacheHits.get(), errors.get(),
				lastFile);
	}

	private Execution findExecution() {
		return executionRepository.findById(executionId)
				.orElseThrow(() -> new IllegalStateException("Execution not found: " + executionId));
	}
}