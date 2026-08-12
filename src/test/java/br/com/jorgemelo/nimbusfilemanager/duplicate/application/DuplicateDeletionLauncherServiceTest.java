package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.Progress;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateDeletePayload;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.settings.application.QuarantineFolderPolicy;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ExecutionLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

@ExtendWith(MockitoExtension.class)
class DuplicateDeletionLauncherServiceTest {

	@TempDir
	private Path tempDir;

	@Mock
	private QuarantineFolderPolicy quarantineFolderPolicy;

	@Mock
	private ExecutionEnqueueService executionEnqueueService;

	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(new ObjectMapper());

	/**
	 * The quarantine root goes in both path columns because it is the one tree
	 * every file is going to; the files themselves are scattered and travel in the
	 * payload.
	 */
	@Test
	void queuesTheQuarantineRootAsColumnsAndTheFilesAsPayload() {
		Path quarantine = tempDir.resolve("quarantine");

		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		when(quarantineFolderPolicy.root()).thenReturn(Optional.of(quarantine));
		when(executionEnqueueService.enqueue(any())).thenAnswer(invocation -> {
			Execution queued = invocation.getArgument(0);

			queued.setStatus(ExecutionStatus.PENDING);
			queued.setExecutionPublicId(UUID.randomUUID());

			return Optional.of(queued);
		});

		launcher().launch(List.of(first, second));

		ArgumentCaptor<Execution> captor = ArgumentCaptor.forClass(Execution.class);

		verify(executionEnqueueService).enqueue(captor.capture());

		Execution queued = captor.getValue();

		Assertions.assertThat(queued.getExecutionType()).isEqualTo(ExecutionType.DEDUP_DELETE);
		Assertions.assertThat(queued.getSourcePath()).isEqualTo(PathUtils.normalize(quarantine));
		Assertions.assertThat(queued.getTargetPath()).isEqualTo(PathUtils.normalize(quarantine));
		Assertions.assertThat(queued.getFilesFound()).isEqualTo(2);
		Assertions.assertThat(queued.getDedupKey()).isNull();

		DuplicateDeletePayload payload = executionPayloadCodec.decode(queued.getRequestPayload(),
				DuplicateDeletePayload.class);

		Assertions.assertThat(payload.schemaVersion()).isEqualTo(DuplicateConstants.DELETE_PAYLOAD_SCHEMA_VERSION);
		Assertions.assertThat(payload.publicIds()).containsExactly(first, second);
	}

	/**
	 * Refused where somebody can be told, rather than becoming a row that fails in
	 * another process minutes later.
	 */
	@Test
	void refusesWhenThereIsNoQuarantineToSendThemTo() {
		when(quarantineFolderPolicy.root()).thenReturn(Optional.empty());

		DuplicateDeletionLauncherService launcher = launcher();

		List<UUID> ids = List.of(UUID.randomUUID());

		Assertions.assertThatThrownBy(() -> launcher.launch(ids)).isInstanceOf(IllegalArgumentException.class);

		verify(executionEnqueueService, never()).enqueue(any());
	}

	@Test
	void refusesARequestThatNamesNoFiles() {
		DuplicateDeletionLauncherService launcher = launcher();

		Assertions.assertThatThrownBy(() -> launcher.launch(List.of()))
				.isInstanceOf(IllegalArgumentException.class);

		verify(executionEnqueueService, never()).enqueue(any());
	}

	/**
	 * These carry no deduplication key, so a refusal is the queue answering
	 * something nobody has described - and a request that silently vanishes is
	 * worse than one that raises where somebody can be told.
	 */
	@Test
	void raisesWhenTheQueueRefusesARequestThatCannotBeADuplicate() {
		when(quarantineFolderPolicy.root()).thenReturn(Optional.of(tempDir.resolve("quarantine")));
		when(executionEnqueueService.enqueue(any())).thenReturn(Optional.empty());

		DuplicateDeletionLauncherService launcher = launcher();

		List<UUID> ids = List.of(UUID.randomUUID());

		Assertions.assertThatThrownBy(() -> launcher.launch(ids)).isInstanceOf(IllegalStateException.class);
	}

	private DuplicateDeletionLauncherService launcher() {
		return new DuplicateDeletionLauncherService(quarantineFolderPolicy, executionEnqueueService,
				executionPayloadCodec,
				new ExecutionMapper(new ExecutionMessageCodec(new ObjectMapper()), mock(ExecutionLabels.class),
						Progress.reader(), Progress.estimator()));
	}
}