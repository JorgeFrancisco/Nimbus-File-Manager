package br.com.jorgemelo.nimbusfilemanager.inventory.application;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestComponent;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryRequest;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryScanRequest;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.ScanOptions;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataOptions;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * Seeds the catalog in integration tests by running a real inventory pass to
 * completion, so what a test observes afterwards is what production would have
 * written.
 *
 * <p>
 * It used to assemble a synchronous Spring Batch {@code JobLauncher} by hand,
 * because the production path launched the job on an async executor and a test
 * cannot assert against work that has not happened yet. With the framework gone
 * the pass is an ordinary method call - {@link InventoryScanRunner} is
 * synchronous, and only the launcher's {@code @Async} wrapper makes it a
 * background task - so this calls exactly what production calls, with nothing
 * standing in for anything.
 */
@TestComponent
public class InventoryBatchTestSeeder {

	private final InventoryScanRunner inventoryScanRunner;
	private final ScanExclusionService scanExclusionService;
	private final ExecutionRepository executionRepository;
	private final ExecutionMapper executionMapper;
	private final String applicationVersion;
	private final Clock clock;

	public InventoryBatchTestSeeder(InventoryScanRunner inventoryScanRunner,
			ScanExclusionService scanExclusionService, ExecutionRepository executionRepository,
			ExecutionMapper executionMapper, @Value("${application.version}") String applicationVersion, Clock clock) {
		this.inventoryScanRunner = inventoryScanRunner;
		this.scanExclusionService = scanExclusionService;
		this.executionRepository = executionRepository;
		this.executionMapper = executionMapper;
		this.applicationVersion = applicationVersion;
		this.clock = clock;
	}

	public ExecutionResponse seed(InventoryRequest request) {
		Execution startedExecution = Execution.builder().executionType(ExecutionType.INVENTORY)
				.status(ExecutionStatus.RUNNING).startedAt(LocalDateTime.now(clock))
				.sourcePath(request.source().toString()).targetPath(null).recursive(request.recursive())
				.executeFlag(true).applicationVersion(applicationVersion)
				.statusMessage(StatusMessage.code(ExecutionMessages.INVENTORY_STARTED)).build();

		Execution execution = executionRepository.save(startedExecution);

		Long executionId = execution.getId();

		inventoryScanRunner.run(execution, scanRequest(request));

		return executionMapper.toResponse(executionRepository.findById(executionId)
				.orElseThrow(() -> new IllegalStateException("Execution not found: " + executionId)));
	}

	private InventoryScanRequest scanRequest(InventoryRequest request) {
		ScanOptions scanOptions = new ScanOptions(request.recursive(), request.includeHidden(), List.of(),
				scanExclusionService.excludedExtensions(), scanExclusionService.excludedFolders());

		return new InventoryScanRequest(request.source(), scanOptions,
				new MetadataOptions(request.calculateHashes(), request.forceAnalysis()));
	}
}