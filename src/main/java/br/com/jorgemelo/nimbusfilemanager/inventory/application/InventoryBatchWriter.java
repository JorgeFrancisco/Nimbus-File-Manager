package br.com.jorgemelo.nimbusfilemanager.inventory.application;

import java.nio.file.Path;
import java.util.List;
import java.util.function.IntConsumer;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryBatchItemResult;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryScanRequest;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.facade.MetadataFacade;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Catalogues one batch of files and reports what it did.
 *
 * <p>
 * Persistence stays where it was - {@code InventoryPersistenceService} still
 * owns the transaction that writes a batch, and the parallel extraction still
 * happens inside it. What changed is the packaging: this was an
 * {@code ItemWriter} in a step-scoped bean, receiving its arguments through
 * Spring Batch's parameter map and keeping its counters as fields. Now it is an
 * ordinary component taking the execution, the request and the tally as
 * arguments, which is the same work with none of the framework.
 */
@Component
public class InventoryBatchWriter {

	private final InventoryPersistenceService inventoryPersistenceService;
	private final MetadataFacade metadataFacade;
	private final ExecutionErrorService executionErrorService;
	private final ExecutionProgressService executionProgressService;
	private final ExecutionCancellationService executionCancellationService;

	public InventoryBatchWriter(InventoryPersistenceService inventoryPersistenceService, MetadataFacade metadataFacade,
			ExecutionErrorService executionErrorService, ExecutionProgressService executionProgressService,
			ExecutionCancellationService executionCancellationService) {
		this.inventoryPersistenceService = inventoryPersistenceService;
		this.metadataFacade = metadataFacade;
		this.executionErrorService = executionErrorService;
		this.executionProgressService = executionProgressService;
		this.executionCancellationService = executionCancellationService;
	}

	public void write(Execution execution, InventoryScanRequest request, List<Path> files,
			InventoryCounters counters) {
		Long executionId = execution.getId();

		int baseFound = counters.addFound(files.size());

		IntConsumer onExtractionProgress = done -> executionProgressService.updateLiveProgress(execution,
				baseFound + done, counters.analyzed(), counters.cacheHits(), counters.errors(),
				ExecutionMessages.extractingMetadata());

		List<InventoryBatchItemResult> results = inventoryPersistenceService.saveOrCacheBatch(files,
				request.sourcePath(), request.metadataOptions(),
				file -> metadataFacade.extract(file, request.metadataOptions()),
				() -> executionCancellationService.isCancelled(executionId), onExtractionProgress);

		Path lastFile = null;

		for (InventoryBatchItemResult result : results) {
			lastFile = result.file();

			if (result.failed()) {
				executionErrorService.save(result.file(), result.exception(), execution);

				counters.countError();

				continue;
			}

			switch (result.result().result()) {
			case ANALYZED -> counters.countAnalyzed();
			case CACHE -> counters.countCacheHit();
			case ERROR -> counters.countError();
			}
		}

		executionProgressService.updateProgress(execution, counters.found(), counters.analyzed(), counters.cacheHits(),
				counters.errors(), lastFile);
	}
}