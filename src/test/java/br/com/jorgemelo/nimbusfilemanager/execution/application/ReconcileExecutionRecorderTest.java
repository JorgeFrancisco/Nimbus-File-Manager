package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

@ExtendWith(MockitoExtension.class)
class ReconcileExecutionRecorderTest {

	@Mock
	private ExecutionRepository executionRepository;

	@Mock
	private ExecutionProgressService executionProgressService;

	@Test
	void recordsReconcileExecutionWhenCatalogWasRepaired() {
		when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		recorder().recordIfRepaired(ExecutionTrigger.TIMER, Path.of("C:/media"), response(2, 3, 1));

		ArgumentCaptor<Execution> saved = ArgumentCaptor.forClass(Execution.class);

		verify(executionRepository).save(saved.capture());

		Execution execution = saved.getValue();

		assertThat(execution.getExecutionType()).isEqualTo(ExecutionType.RECONCILE);
		assertThat(execution.getTriggerEvent()).isEqualTo(ExecutionTrigger.TIMER);
		assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.STARTED);
		assertThat(execution.getStatusMessage().getCode()).isEqualTo(ExecutionMessages.RECONCILE_REPAIRED);
		assertThat(execution.getExecuteFlag()).isTrue();
		assertThat(execution.getApplicationVersion()).isEqualTo("test-version");

		verify(executionProgressService).finish(execution, ExecutionStatus.FINISHED, 0, 0, 0, 0,
				ExecutionMessages.reconcileRepaired(2, 3, 1));
	}

	@Test
	void recordsNothingWhenReconcileRepairedNothing() {
		recorder().recordIfRepaired(ExecutionTrigger.FILE_EVENT, Path.of("C:/media"), response(0, 0, 0));

		verify(executionRepository, never()).save(any());
		verify(executionProgressService, never()).finish(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
	}

	private ReconcileExecutionRecorder recorder() {
		return new ReconcileExecutionRecorder(executionRepository, executionProgressService, Clock.systemUTC(),
				"test-version");
	}

	private OrganizationReconcileResponse response(long renamed, long repairedPaths, long markedMissing) {
		return new OrganizationReconcileResponse("C:/media", true, false, 0, 0, 0, 0, 0, List.of(), List.of(),
				List.of(), renamed, repairedPaths, markedMissing);
	}
}