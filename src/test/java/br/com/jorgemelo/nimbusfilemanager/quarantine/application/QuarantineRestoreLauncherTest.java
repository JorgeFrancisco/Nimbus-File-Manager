package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.Progress;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCompletionWait;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreOptions;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestorePayload;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestorePlan;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.RestoreOutcome;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ExecutionLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * The single restore, and the three answers it can give: the question that
 * stops it before anything is queued, what the move did when it fit inside the
 * budget, and "still coming" when it did not.
 *
 * <p>
 * What is missing here is the point of the class: there is no path that moves a
 * file, with or without a worker alive.
 */
class QuarantineRestoreLauncherTest {

	private final QuarantineRestorePlanner planner = mock(QuarantineRestorePlanner.class);
	private final ExecutionEnqueueService executionEnqueueService = mock(ExecutionEnqueueService.class);
	private final ExecutionCompletionWait executionCompletionWait = mock(ExecutionCompletionWait.class);
	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(new ObjectMapper());
	private final ExecutionMessageCodec executionMessageCodec = new ExecutionMessageCodec(new ObjectMapper());
	private final QuarantineRestoreLauncher launcher = new QuarantineRestoreLauncher(planner, executionEnqueueService,
			executionCompletionWait, executionPayloadCodec, executionMessageCodec,
			new ExecutionMapper(new ExecutionMessageCodec(new ObjectMapper()), new ExecutionLabels(), Progress.reader(),
					Progress.estimator()));

	/**
	 * A question is the end of the request: nothing is queued, because a queue
	 * cannot hold a conversation and the person is right there to answer.
	 */
	@Test
	void answersTheQuestionWithoutQueueingAnything() {
		UUID movementId = UUID.randomUUID();

		QuarantineRestoreResult conflict = new QuarantineRestoreResult(false, RestoreOutcome.CONFLICT.name(),
				"já existe", movementId, null);

		when(planner.plan(eq(movementId), any())).thenReturn(QuarantineRestorePlan.answered(conflict));

		Assertions.assertThat(launcher.restore(movementId, QuarantineRestoreOptions.defaults())).isEqualTo(conflict);

		verify(executionEnqueueService, never()).enqueueOrExisting(any());
	}

	/**
	 * What travels is the conclusion: both ends of the move by name, so the worker
	 * locks exactly those two paths, and the movement as the deduplication key, so
	 * a second click is answered with the restore already coming.
	 */
	@Test
	void queuesTheDecidedMoveNamingBothEnds(@TempDir Path tmp) {
		UUID movementId = UUID.randomUUID();

		Path quarantine = tmp.resolve("trash").resolve("10__a.jpg");
		Path destination = tmp.resolve("library").resolve("a.jpg");

		planned(movementId, quarantine, destination);

		launcher.restore(movementId, QuarantineRestoreOptions.defaults());

		Execution queued = captureQueued();

		Assertions.assertThat(queued.getExecutionType()).isEqualTo(ExecutionType.QUARANTINE_RESTORE);
		Assertions.assertThat(queued.getSourcePath()).isEqualTo(PathUtils.normalize(quarantine));
		Assertions.assertThat(queued.getTargetPath()).isEqualTo(PathUtils.normalize(destination));
		Assertions.assertThat(queued.getDedupKey()).isEqualTo(movementId.toString());
		Assertions.assertThat(queued.getFilesFound()).isEqualTo(1);

		QuarantineRestorePayload payload = executionPayloadCodec.decode(queued.getRequestPayload(),
				QuarantineRestorePayload.class);

		Assertions.assertThat(payload.schemaVersion()).isEqualTo(QuarantineConstants.PAYLOAD_SCHEMA_VERSION);
		Assertions.assertThat(payload.movementIds()).containsExactly(movementId);
		Assertions.assertThat(payload.destination()).isEqualTo(PathUtils.normalize(destination));
	}

	@Test
	void answersWithTheRestoreWhenTheWorkFinishesInTime(@TempDir Path tmp) {
		UUID movementId = UUID.randomUUID();

		Path destination = tmp.resolve("library").resolve("a.jpg");

		planned(movementId, tmp.resolve("trash").resolve("10__a.jpg"), destination);

		when(executionCompletionWait.awaitTerminal(anyLong(), any()))
				.thenReturn(Optional.of(finished(ExecutionStatus.FINISHED, 1)));

		QuarantineRestoreResult result = launcher.restore(movementId, QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.success()).isTrue();
		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.RESTORED.name());
		Assertions.assertThat(result.restoredPath()).isEqualTo(PathUtils.normalize(destination));
	}

	/**
	 * The row closed without the file having moved - the destination was taken in
	 * the meantime, the copy was purged. That is reported with the sentence the
	 * execution recorded, and the person decides what to do next.
	 */
	@Test
	void answersWithWhatTheRowSaysWhenNothingWasRestored(@TempDir Path tmp) {
		UUID movementId = UUID.randomUUID();

		planned(movementId, tmp.resolve("trash").resolve("10__a.jpg"), tmp.resolve("library").resolve("a.jpg"));

		when(executionCompletionWait.awaitTerminal(anyLong(), any()))
				.thenReturn(Optional.of(finished(ExecutionStatus.FINISHED, 0)));

		QuarantineRestoreResult result = launcher.restore(movementId, QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.success()).isFalse();
		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.ERROR.name());
		Assertions.assertThat(result.message()).isNotBlank();
	}

	/** A run that ended with errors is not a restore, however many it moved. */
	@Test
	void doesNotCallARunThatEndedWithErrorsARestore(@TempDir Path tmp) {
		UUID movementId = UUID.randomUUID();

		planned(movementId, tmp.resolve("trash").resolve("10__a.jpg"), tmp.resolve("library").resolve("a.jpg"));

		when(executionCompletionWait.awaitTerminal(anyLong(), any()))
				.thenReturn(Optional.of(finished(ExecutionStatus.FINISHED_WITH_ERRORS, 1)));

		Assertions.assertThat(launcher.restore(movementId, QuarantineRestoreOptions.defaults()).outcome())
				.isEqualTo(RestoreOutcome.ERROR.name());
	}

	/**
	 * Past the budget the request is not lost and not repeated: it was accepted,
	 * and the screen is told to follow the execution instead of the reply.
	 */
	@Test
	void answersThatTheRestoreIsStillComingWhenTheBudgetRunsOut(@TempDir Path tmp) {
		UUID movementId = UUID.randomUUID();

		planned(movementId, tmp.resolve("trash").resolve("10__a.jpg"), tmp.resolve("library").resolve("a.jpg"));

		when(executionCompletionWait.awaitTerminal(anyLong(), any())).thenReturn(Optional.empty());

		QuarantineRestoreResult result = launcher.restore(movementId, QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.PENDING.name());
		Assertions.assertThat(result.success()).isTrue();
		Assertions.assertThat(result.message()).isNotBlank();
		Assertions.assertThat(result.restoredPath()).isNull();
	}

	private void planned(UUID movementId, Path quarantine, Path destination) {
		when(planner.plan(eq(movementId), any())).thenReturn(QuarantineRestorePlan.move(quarantine, destination));
		when(executionEnqueueService.enqueueOrExisting(any())).thenAnswer(invocation -> {
			Execution request = invocation.getArgument(0);

			request.setId(1L);
			request.setStatus(ExecutionStatus.PENDING);

			return request;
		});
	}

	private Execution captureQueued() {
		ArgumentCaptor<Execution> queued = ArgumentCaptor.captor();

		verify(executionEnqueueService).enqueueOrExisting(queued.capture());

		return queued.getValue();
	}

	private Execution finished(ExecutionStatus status, int filesMoved) {
		return Execution.builder().id(1L).executionPublicId(UUID.randomUUID())
				.executionType(ExecutionType.QUARANTINE_RESTORE).status(status).filesFound(1).filesAnalyzed(1)
				.cacheHits(0).filesMoved(filesMoved).errors(0)
				.statusMessage(StatusMessage.coded("backend.quarantine.batchCompleted", "[0,1,0,0]")).build();
	}
}