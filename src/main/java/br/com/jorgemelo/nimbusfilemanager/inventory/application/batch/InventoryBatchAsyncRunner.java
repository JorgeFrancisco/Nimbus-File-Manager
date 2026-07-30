package br.com.jorgemelo.nimbusfilemanager.inventory.application.batch;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.AsyncConfig;

/**
 * Launches the inventory Spring Batch job in the background so the web request
 * that triggered it can return immediately with an executionId to poll. Must
 * live in its own bean (not inside InventoryBatchLauncherService) so the
 * {@code @Async} proxy is honored - self-invocation would run synchronously and
 * defeat the purpose. Mirrors the (now removed) InventoryAsyncRunner.
 *
 * <p>
 * {@link JobLauncher#run} only throws when the job fails to even <em>start</em>
 * (invalid parameters, duplicate instance, already running); once it starts,
 * all success/failure/ cancellation outcomes are handled by
 * {@link InventoryJobExecutionListener}.
 */
@Service
public class InventoryBatchAsyncRunner {

	private final JobLauncher jobLauncher;
	private final Job inventoryJob;
	private final ExecutionProgressService executionProgressService;

	public InventoryBatchAsyncRunner(JobLauncher jobLauncher, Job inventoryJob,
			ExecutionProgressService executionProgressService) {
		this.jobLauncher = jobLauncher;
		this.inventoryJob = inventoryJob;
		this.executionProgressService = executionProgressService;
	}

	@Async(AsyncConfig.TASK_EXECUTOR)
	public void run(JobParameters jobParameters, Execution execution) {
		try {
			jobLauncher.run(inventoryJob, jobParameters);
		} catch (Exception e) {
			executionProgressService.fail(execution, ExecutionMessages.inventoryStartFailed(e.getMessage()));
		}
	}
}