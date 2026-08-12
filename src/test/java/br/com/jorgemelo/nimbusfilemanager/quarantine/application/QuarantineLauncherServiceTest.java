package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantinePurgePayload;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestorePayload;
import br.com.jorgemelo.nimbusfilemanager.settings.application.QuarantineFolderPolicy;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ExecutionLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

@ExtendWith(MockitoExtension.class)
class QuarantineLauncherServiceTest {

	@TempDir
	private Path tempDir;

	@Mock
	private QuarantineFolderPolicy quarantineFolderPolicy;

	@Mock
	private QuarantineRetentionPolicy quarantineRetentionPolicy;

	@Mock
	private ExecutionEnqueueService executionEnqueueService;

	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(new ObjectMapper());

	/**
	 * The quarantine folder goes in both columns because it is the tree every one
	 * of these touches; the items travel in the payload, since no folder describes
	 * a selection somebody made one card at a time.
	 */
	@Test
	void queuesTheQuarantineRootAsColumnsAndTheItemsAsPayload() {
		Path quarantine = tempDir.resolve("quarantine");

		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		when(quarantineFolderPolicy.root()).thenReturn(Optional.of(quarantine));
		asTheQueueWouldAnswer();

		launcher().launchRestore(List.of(first, second));

		Execution queued = queued();

		Assertions.assertThat(queued.getExecutionType()).isEqualTo(ExecutionType.QUARANTINE_RESTORE);
		Assertions.assertThat(queued.getSourcePath()).isEqualTo(PathUtils.normalize(quarantine));
		Assertions.assertThat(queued.getTargetPath()).isEqualTo(PathUtils.normalize(quarantine));
		Assertions.assertThat(queued.getFilesFound()).isEqualTo(2);
		Assertions.assertThat(queued.getDedupKey()).isNull();

		QuarantineRestorePayload payload = executionPayloadCodec.decode(queued.getRequestPayload(),
				QuarantineRestorePayload.class);

		Assertions.assertThat(payload.schemaVersion()).isEqualTo(QuarantineConstants.PAYLOAD_SCHEMA_VERSION);
		Assertions.assertThat(payload.movementIds()).containsExactly(first, second);
	}

	@Test
	void queuesAPickedPurgeWithTheItemsAndNoWindow() {
		UUID picked = UUID.randomUUID();

		when(quarantineFolderPolicy.root()).thenReturn(Optional.of(tempDir.resolve("quarantine")));
		asTheQueueWouldAnswer();

		launcher().launchPurge(List.of(picked));

		Execution queued = queued();

		Assertions.assertThat(queued.getExecutionType()).isEqualTo(ExecutionType.QUARANTINE_PURGE);
		Assertions.assertThat(queued.getTriggerEvent()).isNull();

		QuarantinePurgePayload payload = executionPayloadCodec.decode(queued.getRequestPayload(),
				QuarantinePurgePayload.class);

		Assertions.assertThat(payload.retentionDays()).isNull();
		Assertions.assertThat(payload.movementIds()).containsExactly(picked);
	}

	/**
	 * The daily pass carries its window instead of a list, so what is overdue is
	 * decided when the purge runs rather than when it was asked for.
	 */
	@Test
	void queuesTheDailyPassWithItsWindowAndNoItems() {
		when(quarantineFolderPolicy.root()).thenReturn(Optional.of(tempDir.resolve("quarantine")));
		when(quarantineRetentionPolicy.hasOverdue(90)).thenReturn(true);
		asTheQueueWouldAnswer();

		Assertions.assertThat(launcher().launchScheduledPurge(90)).isPresent();

		Execution queued = queued();

		Assertions.assertThat(queued.getTriggerEvent()).isEqualTo(ExecutionTrigger.TIMER);

		QuarantinePurgePayload payload = executionPayloadCodec.decode(queued.getRequestPayload(),
				QuarantinePurgePayload.class);

		Assertions.assertThat(payload.retentionDays()).isEqualTo(90);
		Assertions.assertThat(payload.movementIds()).isNull();
	}

	/**
	 * A day with nothing overdue leaves no row at all: a daily "0 purged" would
	 * bury the rows that record a real deletion.
	 */
	@Test
	void queuesNoDailyPassWhenNothingIsOverdue() {
		when(quarantineFolderPolicy.root()).thenReturn(Optional.of(tempDir.resolve("quarantine")));
		when(quarantineRetentionPolicy.hasOverdue(90)).thenReturn(false);

		Assertions.assertThat(launcher().launchScheduledPurge(90)).isEmpty();

		verify(executionEnqueueService, never()).enqueue(any());
	}

	/**
	 * Every one of these is defined by the quarantine folder, so without one there
	 * is no tree for the worker to hold and the row could never run: the dispatcher
	 * would refuse it and the person would be left with a failed execution for
	 * something that was impossible when they clicked. Refused here, where there is
	 * still somebody to tell, and with the sentence to tell them.
	 */
	@Test
	void refusesAPurgeWithTheReasonWhenTheQuarantineFolderIsNotConfigured() {
		when(quarantineFolderPolicy.root()).thenReturn(Optional.empty());

		QuarantineLauncherService launcher = launcher();

		List<UUID> ids = List.of(UUID.randomUUID());

		Assertions.assertThatThrownBy(() -> launcher.launchPurge(ids))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("quarentena");

		verify(executionEnqueueService, never()).enqueue(any());
	}

	@Test
	void refusesARestoreWhenTheQuarantineFolderIsNotConfigured() {
		when(quarantineFolderPolicy.root()).thenReturn(Optional.empty());

		QuarantineLauncherService launcher = launcher();

		List<UUID> ids = List.of(UUID.randomUUID());

		Assertions.assertThatThrownBy(() -> launcher.launchRestore(ids))
				.isInstanceOf(IllegalArgumentException.class);

		verify(executionEnqueueService, never()).enqueue(any());
	}

	/**
	 * The timer gets silence rather than the refusal: nobody is watching it, and a
	 * daily pass that raised over a folder the user never set would be an error
	 * every day for a feature they are not using. Nothing is even asked about
	 * retention, because there is nothing that could come of the answer.
	 */
	@Test
	void queuesNoDailyPassAndAsksNothingWhenTheQuarantineFolderIsNotConfigured() {
		when(quarantineFolderPolicy.root()).thenReturn(Optional.empty());

		Assertions.assertThat(launcher().launchScheduledPurge(90)).isEmpty();

		verify(quarantineRetentionPolicy, never()).hasOverdue(anyInt());
		verify(executionEnqueueService, never()).enqueue(any());
	}

	/**
	 * These carry no deduplication key, so a refusal is the queue answering
	 * something nobody has described - louder than a request that silently
	 * vanishes.
	 */
	@Test
	void raisesWhenTheQueueRefusesARequestThatCannotBeADuplicate() {
		when(quarantineFolderPolicy.root()).thenReturn(Optional.of(tempDir.resolve("quarantine")));
		when(executionEnqueueService.enqueue(any())).thenReturn(Optional.empty());

		QuarantineLauncherService launcher = launcher();

		List<UUID> ids = List.of(UUID.randomUUID());

		Assertions.assertThatThrownBy(() -> launcher.launchRestore(ids)).isInstanceOf(IllegalStateException.class);
	}

	private void asTheQueueWouldAnswer() {
		when(executionEnqueueService.enqueue(any())).thenAnswer(invocation -> {
			Execution queued = invocation.getArgument(0);

			queued.setStatus(ExecutionStatus.PENDING);
			queued.setExecutionPublicId(UUID.randomUUID());

			return Optional.of(queued);
		});
	}

	private Execution queued() {
		ArgumentCaptor<Execution> captor = ArgumentCaptor.forClass(Execution.class);

		verify(executionEnqueueService).enqueue(captor.capture());

		return captor.getValue();
	}

	private QuarantineLauncherService launcher() {
		return new QuarantineLauncherService(quarantineFolderPolicy, quarantineRetentionPolicy,
				executionEnqueueService, executionPayloadCodec, new ExecutionMessageCodec(new ObjectMapper()),
				new ExecutionMapper(new ExecutionMessageCodec(new ObjectMapper()), mock(ExecutionLabels.class),
						Progress.reader(), Progress.estimator()));
	}
}