package br.com.jorgemelo.nimbusfilemanager.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.Progress;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationPathKey;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ExecutionLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Starting an inventory: the row is written first, so the screen has something
 * to poll before any file has been touched, and the options are resolved once
 * rather than re-read while the pass runs.
 *
 * <p>
 * What happens when the same folder is asked for twice is no longer this
 * class's answer - it belongs to the enqueue, which is where the database
 * refuses the duplicate - and is tested there.
 */
class InventoryLauncherServiceTest {

	private final ExecutionEnqueueService executionEnqueueService = mock(ExecutionEnqueueService.class);

	private final InventoryLauncherService service = new InventoryLauncherService(executionEnqueueService,
			new ExecutionMapper(new ExecutionMessageCodec(new ObjectMapper()), new ExecutionLabels(), Progress.reader(),
					Progress.estimator()), "6.0.0");

	@Test
	void queuesTheInventoryInsteadOfRunningIt(@TempDir Path folder) {
		when(executionEnqueueService.enqueueOrExisting(any()))
				.thenAnswer(invocation -> queued(invocation.getArgument(0)));

		ExecutionResponse response = service.launch(request(folder), ExecutionTrigger.MANUAL);

		ArgumentCaptor<Execution> queued = ArgumentCaptor.captor();

		verify(executionEnqueueService).enqueueOrExisting(queued.capture());

		assertThat(queued.getValue().getExecutionType()).isEqualTo(ExecutionType.INVENTORY);
		assertThat(queued.getValue().getSourcePath()).isEqualTo(folder.toString());
		assertThat(queued.getValue().getDedupKey()).isEqualTo(OperationPathKey.canonical(folder));
		assertThat(response).isNotNull();
	}

	/**
	 * What the enqueue service does to a request before it becomes a row - the
	 * status the queue is entered in.
	 */
	private Execution queued(Execution request) {
		request.setStatus(ExecutionStatus.PENDING);

		return request;
	}

	private InventoryRequest request(Path folder) {
		return new InventoryRequest(folder.toString(), true, false, true, false);
	}
}