package br.com.jorgemelo.nimbusfilemanager.inventory.application.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.JobParameters;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ExecutionLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

@ExtendWith(MockitoExtension.class)
class InventoryBatchLauncherServiceTest {

	@Mock
	private ExecutionRepository executionRepository;

	@Mock
	private ExecutionProgressService executionProgressService;

	@Mock
	private InventoryBatchAsyncRunner inventoryBatchAsyncRunner;

	private final ExecutionMapper executionMapper = new ExecutionMapper(new ExecutionMessageCodec(new ObjectMapper()),
			new ExecutionLabels());

	@Test
	void launchShouldSaveStartedExecutionBuildJobParametersAndDispatchToRunner() {
		Execution execution = Execution.builder().id(11L).executionType(ExecutionType.INVENTORY)
				.status(ExecutionStatus.STARTED).build();

		when(executionRepository.save(any())).thenReturn(execution);

		var response = service().launch(request(), ExecutionTrigger.MANUAL);

		Assertions.assertThat(response.executionId()).isEqualTo(UuidV7.fromLegacy(11L));
		Assertions.assertThat(response.status()).isEqualTo(ExecutionStatus.STARTED.name());

		ArgumentCaptor<Execution> savedExecution = ArgumentCaptor.forClass(Execution.class);

		verify(executionRepository).save(savedExecution.capture());

		Assertions.assertThat(savedExecution.getValue().getTriggerEvent()).isEqualTo(ExecutionTrigger.MANUAL);

		verify(executionProgressService).markInterruptedExecutions();
		verify(executionProgressService).updateStatus(execution, ExecutionStatus.STARTED, ExecutionStepType.STARTED,
				ExecutionMessages.inventoryStarted());

		ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);

		verify(inventoryBatchAsyncRunner).run(captor.capture(), eq(execution));

		JobParameters params = captor.getValue();

		Assertions.assertThat(params.getLong("executionId")).isEqualTo(11L);
		Assertions.assertThat(params.getString("sourcePath")).isEqualTo(request().source().toString());
		Assertions.assertThat(params.getString("recursive")).isEqualTo("true");
		Assertions.assertThat(params.getString("includeHidden")).isEqualTo("true");
		Assertions.assertThat(params.getString("calculateHashes")).isEqualTo("true");
		Assertions.assertThat(params.getString("forceAnalysis")).isEqualTo("false");
	}

	private InventoryBatchLauncherService service() {
		return new InventoryBatchLauncherService(executionRepository, executionProgressService,
				inventoryBatchAsyncRunner, executionMapper, "test-version", Clock.systemDefaultZone());
	}

	private InventoryRequest request() {
		return new InventoryRequest("C:/input", true, true, true, false);
	}
}