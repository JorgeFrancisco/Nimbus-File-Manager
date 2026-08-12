package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.Progress;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCompletionWait;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineCleanupPayload;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineCleanupResult;
import br.com.jorgemelo.nimbusfilemanager.settings.application.QuarantineFolderPolicy;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ExecutionLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Asking for the records of absent files to be cleared, and the three things
 * that can come back.
 *
 * <p>
 * The one worth a test of its own is the quiet case: nothing absent queues
 * nothing at all, so the executions screen stays a list of things that happened
 * rather than of times somebody clicked a button.
 */
class QuarantineCleanupLauncherTest {

	private static final String QUARANTINE_ROOT = "D:/quarentena";

	private final QuarantineAbsenceScan quarantineAbsenceScan = mock(QuarantineAbsenceScan.class);
	private final QuarantineFolderPolicy quarantineFolderPolicy = mock(QuarantineFolderPolicy.class);
	private final ExecutionEnqueueService executionEnqueueService = mock(ExecutionEnqueueService.class);
	private final ExecutionCompletionWait executionCompletionWait = mock(ExecutionCompletionWait.class);
	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(new ObjectMapper());
	private final QuarantineCleanupLauncher launcher = new QuarantineCleanupLauncher(quarantineAbsenceScan,
			quarantineFolderPolicy, executionEnqueueService, executionCompletionWait, executionPayloadCodec,
			new ExecutionMessageCodec(new ObjectMapper()),
			new ExecutionMapper(new ExecutionMessageCodec(new ObjectMapper()), new ExecutionLabels(), Progress.reader(),
					Progress.estimator()));

	/**
	 * Nothing absent is not an operation that cleared nothing - it is an operation
	 * that never had to run. The person is told so in words, because a zero on the
	 * status line reads as the button having worked.
	 */
	@Test
	void queuesNothingWhenNoRecordIsAbsent() {
		when(quarantineFolderPolicy.root()).thenReturn(Optional.of(Path.of(QUARANTINE_ROOT)));
		when(quarantineAbsenceScan.absent()).thenReturn(List.of());

		QuarantineCleanupResult result = launcher.clearAbsent();

		Assertions.assertThat(result.removed()).isZero();
		Assertions.assertThat(result.pending()).isFalse();
		Assertions.assertThat(result.message()).isNotBlank();

		verify(executionEnqueueService, never()).enqueueOrExisting(any());
	}

	/**
	 * The shortlist travels with the request, and no deduplication key: two
	 * readings taken a minute apart are two different sets, and telling them apart
	 * is what the second look under the lock already does per item.
	 */
	@Test
	void queuesTheShortlistItRead() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		queueing(List.of(first, second));

		launcher.clearAbsent();

		Execution queued = captureQueued();

		Assertions.assertThat(queued.getExecutionType()).isEqualTo(ExecutionType.QUARANTINE_CLEANUP);
		Assertions.assertThat(queued.getFilesFound()).isEqualTo(2);
		Assertions.assertThat(queued.getDedupKey()).isNull();

		QuarantineCleanupPayload payload = executionPayloadCodec.decode(queued.getRequestPayload(),
				QuarantineCleanupPayload.class);

		Assertions.assertThat(payload.schemaVersion()).isEqualTo(QuarantineConstants.PAYLOAD_SCHEMA_VERSION);
		Assertions.assertThat(payload.movementIds()).containsExactly(first, second);
	}

	@Test
	void answersWithWhatTheRunClearedWhenItFinishesInTime() {
		queueing(List.of(UUID.randomUUID()));

		when(executionCompletionWait.awaitTerminal(anyLong(), any())).thenReturn(Optional.of(finished(1)));

		QuarantineCleanupResult result = launcher.clearAbsent();

		Assertions.assertThat(result.removed()).isEqualTo(1);
		Assertions.assertThat(result.pending()).isFalse();
		Assertions.assertThat(result.message()).isNotBlank();
	}

	@Test
	void answersThatTheCleanupIsStillComingWhenTheBudgetRunsOut() {
		queueing(List.of(UUID.randomUUID()));

		when(executionCompletionWait.awaitTerminal(anyLong(), any())).thenReturn(Optional.empty());

		QuarantineCleanupResult result = launcher.clearAbsent();

		Assertions.assertThat(result.pending()).isTrue();
		Assertions.assertThat(result.removed()).isZero();
		Assertions.assertThat(result.message()).isNotBlank();
	}

	/**
	 * The request names the quarantine folder, and that is not decoration: this
	 * clears records of files under the same port that deletes them, so the
	 * execution has to take the path locks over that tree. Naming nothing would
	 * have it run alongside a restore or a purge working on the very same files -
	 * and, since a handler that reaches the file port may not opt out of the
	 * locks, it would simply refuse to run at all.
	 */
	@Test
	void namesTheQuarantineFolderSoThePathLocksApplyToIt() {
		queueing(List.of(UUID.randomUUID()));

		launcher.clearAbsent();

		Assertions.assertThat(captureQueued().getSourcePath()).isEqualTo(PathUtils.normalize(Path.of(QUARANTINE_ROOT)));
	}

	/**
	 * The request could not be made at all, so it is not made: nothing is even
	 * scanned. Asking first matters twice over - the row would name no tree and
	 * could not run, and a scan with no folder to look in reports every single
	 * record as absent, which is the one answer this operation must never act on.
	 */
	@Test
	void clearsNothingAndSaysWhyWhenTheQuarantineFolderIsNotConfigured() {
		when(quarantineFolderPolicy.root()).thenReturn(Optional.empty());

		QuarantineCleanupResult result = launcher.clearAbsent();

		Assertions.assertThat(result.removed()).isZero();
		Assertions.assertThat(result.pending()).isFalse();
		Assertions.assertThat(result.message()).isNotBlank();

		verify(quarantineAbsenceScan, never()).absent();
		verify(executionEnqueueService, never()).enqueueOrExisting(any());
	}

	private void queueing(List<UUID> absent) {
		when(quarantineFolderPolicy.root()).thenReturn(Optional.of(Path.of(QUARANTINE_ROOT)));
		when(quarantineAbsenceScan.absent()).thenReturn(absent);
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

	private Execution finished(int removed) {
		return Execution.builder().id(1L).executionPublicId(UUID.randomUUID())
				.executionType(ExecutionType.QUARANTINE_CLEANUP).status(ExecutionStatus.FINISHED).filesFound(1)
				.filesAnalyzed(1).cacheHits(0).filesMoved(removed).errors(0)
				.statusMessage(StatusMessage.coded("backend.quarantine.cleanupCompleted", "[1,0]")).build();
	}
}