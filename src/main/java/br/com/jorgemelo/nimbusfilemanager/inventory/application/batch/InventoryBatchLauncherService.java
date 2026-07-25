package br.com.jorgemelo.nimbusfilemanager.inventory.application.batch;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * Entry point the web layer (Onboarding and manual "Inventario" screens) calls
 * to kick off an inventory run. Creates the {@link Execution} row, then launches
 * the Spring Batch job in the background via {@link InventoryBatchAsyncRunner} so
 * the request can redirect to the progress screen right away.
 */
@Service
public class InventoryBatchLauncherService {

	private final ExecutionRepository executionRepository;
	private final ExecutionProgressService executionProgressService;
	private final InventoryBatchAsyncRunner inventoryBatchAsyncRunner;
	private final ExecutionMapper executionMapper;
	private final String applicationVersion;
	private final Clock clock;

	public InventoryBatchLauncherService(ExecutionRepository executionRepository,
			ExecutionProgressService executionProgressService, InventoryBatchAsyncRunner inventoryBatchAsyncRunner,
			ExecutionMapper executionMapper, @Value("${application.version}") String applicationVersion, Clock clock) {
		this.executionRepository = executionRepository;
		this.executionProgressService = executionProgressService;
		this.inventoryBatchAsyncRunner = inventoryBatchAsyncRunner;
		this.executionMapper = executionMapper;
		this.applicationVersion = applicationVersion;
		this.clock = clock;
	}

	public ExecutionResponse launch(InventoryRequest request, ExecutionTrigger trigger) {
		executionProgressService.markInterruptedExecutions();

		Execution startedExecution = Execution.builder().executionType(ExecutionType.INVENTORY)
				.status(ExecutionStatus.STARTED).triggerEvent(trigger).startedAt(LocalDateTime.now(clock))
				.sourcePath(request.source().toString()).targetPath(null).recursive(request.recursive())
				.executeFlag(true).applicationVersion(applicationVersion)
				.statusMessage(StatusMessage.code(ExecutionMessages.INVENTORY_STARTED))
				.build();

		Execution execution = executionRepository.save(startedExecution);

		executionProgressService.updateStatus(execution, ExecutionStatus.STARTED, ExecutionStepType.STARTED,
				ExecutionMessages.inventoryStarted());

		JobParameters jobParameters = new JobParametersBuilder().addLong("executionId", execution.getId())
				.addString("sourcePath", request.source().toString())
				.addString("recursive", Boolean.toString(request.recursive()))
				.addString("includeHidden", Boolean.toString(request.includeHidden()))
				.addString("calculateHashes", Boolean.toString(request.calculateHashes()))
				.addString("forceAnalysis", Boolean.toString(request.forceAnalysis())).toJobParameters();

		inventoryBatchAsyncRunner.run(jobParameters, execution);

		return executionMapper.toResponse(execution);
	}
}