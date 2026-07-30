package br.com.jorgemelo.nimbusfilemanager.inventory.application.batch;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

@ExtendWith(MockitoExtension.class)
class InventoryBatchAsyncRunnerTest {

	@Mock
	private JobLauncher jobLauncher;

	@Mock
	private Job inventoryJob;

	@Mock
	private ExecutionProgressService executionProgressService;

	@Test
	void runShouldLaunchTheJobAndNotTouchExecutionProgressWhenStartupSucceeds() throws Exception {
		Execution execution = Execution.builder().id(1L).build();

		JobParameters params = new JobParametersBuilder().addLong("executionId", 1L).toJobParameters();

		runner().run(params, execution);

		verify(jobLauncher).run(inventoryJob, params);
		verifyNoInteractions(executionProgressService);
	}

	@Test
	void runShouldMarkExecutionAsFailedWhenTheJobFailsToStart() throws Exception {
		Execution execution = Execution.builder().id(1L).build();

		JobParameters params = new JobParametersBuilder().addLong("executionId", 1L).toJobParameters();

		when(jobLauncher.run(inventoryJob, params)).thenThrow(new JobParametersInvalidException("invalid params"));

		runner().run(params, execution);

		verify(executionProgressService).fail(execution, ExecutionMessages.inventoryStartFailed("invalid params"));
	}

	private InventoryBatchAsyncRunner runner() {
		return new InventoryBatchAsyncRunner(jobLauncher, inventoryJob, executionProgressService);
	}
}