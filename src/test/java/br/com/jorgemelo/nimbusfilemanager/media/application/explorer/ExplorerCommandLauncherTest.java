package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.Progress;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCompletionWait;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationPathKey;
import br.com.jorgemelo.nimbusfilemanager.media.application.constants.ExplorerMessages;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.ExplorerActionResult;
import br.com.jorgemelo.nimbusfilemanager.settings.application.QuarantineFolderPolicy;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ExecutionLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerAvailability;
import br.com.jorgemelo.nimbusfilemanager.worker.application.dto.WorkerAvailabilityResponse;

/**
 * The Files screen asking for work, and the three answers it can get: refused
 * before anything was queued, carried out inside the budget, or accepted and
 * still coming.
 *
 * <p>
 * The one thing that must never happen is missing from these tests because it
 * is missing from the class: there is no path here that does the work itself,
 * with or without a worker alive.
 */
class ExplorerCommandLauncherTest {

	private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

	private final ExplorerDeletionGuard guard = mock(ExplorerDeletionGuard.class);
	private final QuarantineFolderPolicy quarantineFolderPolicy = mock(QuarantineFolderPolicy.class);
	private final ExecutionEnqueueService executionEnqueueService = mock(ExecutionEnqueueService.class);
	private final ExecutionCompletionWait executionCompletionWait = mock(ExecutionCompletionWait.class);
	private final WorkerAvailability workerAvailability = mock(WorkerAvailability.class);
	private final MessageSource messages = messageSource();

	private ExplorerCommandLauncher launcher() {
		when(guard.refusal(any())).thenReturn(Optional.empty());
		when(executionEnqueueService.enqueueOrExisting(any())).thenAnswer(invocation -> queued(invocation
				.getArgument(0)));
		when(workerAvailability.current()).thenReturn(new WorkerAvailabilityResponse(true, 1, null));

		ExecutionLabels labels = new ExecutionLabels();

		labels.setMessageSource(messages);

		ExecutionMapper mapper = new ExecutionMapper(new ExecutionMessageCodec(new ObjectMapper()), labels,
				Progress.reader(), Progress.estimator());

		mapper.setMessageSource(messages);

		ExplorerCommandLauncher launcher = new ExplorerCommandLauncher(guard, quarantineFolderPolicy,
				executionEnqueueService, executionCompletionWait, mapper, workerAvailability);

		launcher.setMessageSource(messages);

		return launcher;
	}

	@BeforeEach
	void useThePortugueseBundle() {
		LocaleContextHolder.setLocale(PT_BR);
	}

	@AfterEach
	void releaseTheLocale() {
		LocaleContextHolder.resetLocaleContext();
	}

	@Test
	void refusesANameCarryingAPathSeparator(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		ExplorerActionResult result = launcher().rename(file, "../escaped.jpg");

		Assertions.assertThat(result.success()).isFalse();
		Assertions.assertThat(result.message()).isEqualTo(expected("backend.files.renameInvalidName"));

		verify(executionEnqueueService, never()).enqueueOrExisting(any());
	}

	@Test
	void refusesABlankName(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		Assertions.assertThat(launcher().rename(file, "   ").message())
				.isEqualTo(expected("backend.files.renameInvalidName"));
	}

	@Test
	void refusesANullName(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		Assertions.assertThat(launcher().rename(file, null).message())
				.isEqualTo(expected("backend.files.renameInvalidName"));
	}

	/**
	 * A filesystem root has no parent to rename inside of. The guard already
	 * refuses it in production; this pins that nothing dereferences the missing
	 * parent even when asked directly.
	 */
	@Test
	void refusesRenamingSomethingWithoutAParent(@TempDir Path folder) {
		Assertions.assertThat(launcher().rename(folder.getRoot(), "novo").message())
				.isEqualTo(expected("backend.files.renameInvalidName"));
	}

	/**
	 * Overwriting the neighbour would destroy a file the user never selected, so
	 * the collision is refused by name instead of queued and resolved silently.
	 */
	@Test
	void refusesWhenSomethingAlreadyHasTheTargetName(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		Files.createFile(folder.resolve("taken.jpg"));

		Assertions.assertThat(launcher().rename(file, "taken.jpg").message())
				.isEqualTo(expected("backend.files.renameTargetExists", "taken.jpg"));

		verify(executionEnqueueService, never()).enqueueOrExisting(any());
	}

	@Test
	void refusesEverythingTheGuardRefuses(@TempDir Path folder, @TempDir Path quarantine) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		ExplorerCommandLauncher launcher = launcher();

		when(guard.refusal(any())).thenReturn(Optional.of(ExplorerMessages.pathGone()));
		when(quarantineFolderPolicy.root()).thenReturn(Optional.of(quarantine));

		Assertions.assertThat(launcher.rename(file, "holiday.jpg").message())
				.isEqualTo(expected("backend.files.pathGone"));
		Assertions.assertThat(launcher.quarantine(file).message()).isEqualTo(expected("backend.files.pathGone"));
		Assertions.assertThat(launcher.deletePermanently(file).message())
				.isEqualTo(expected("backend.files.pathGone"));

		verify(executionEnqueueService, never()).enqueueOrExisting(any());
	}

	/**
	 * Whether there is a quarantine to send files to is decided while somebody is
	 * looking: a request that cannot be right should be refused with a message
	 * rather than become a row that fails in another process.
	 */
	@Test
	void refusesQuarantineWhileTheQuarantineFolderIsUnset(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		when(quarantineFolderPolicy.root()).thenReturn(Optional.empty());

		Assertions.assertThat(launcher().quarantine(file).message())
				.isEqualTo(expected("backend.files.quarantineNotConfigured"));

		verify(executionEnqueueService, never()).enqueueOrExisting(any());
	}

	@Test
	void queuesARenameNamingBothEndsSoTheWorkerCanLockThem(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		launcher().rename(file, "holiday.jpg");

		Execution queued = captureQueued();

		Assertions.assertThat(queued.getExecutionType()).isEqualTo(ExecutionType.EXPLORER_RENAME);
		Assertions.assertThat(queued.getSourcePath()).isEqualTo(PathUtils.normalize(file));
		Assertions.assertThat(queued.getTargetPath()).isEqualTo(PathUtils.normalize(folder.resolve("holiday.jpg")));
		Assertions.assertThat(queued.getDedupKey())
				.isEqualTo(OperationPathKey.canonical(file) + ">" + "holiday.jpg");
	}

	/**
	 * Two clicks asking for the same rename are one request; renaming the same
	 * file to two different names is two, and a key made only of the path would
	 * quietly drop one of them.
	 */
	@Test
	void keepsTwoDifferentRenamesOfTheSameFileApart(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		ExplorerCommandLauncher launcher = launcher();

		launcher.rename(file, "holiday.jpg");
		launcher.rename(file, "vacation.jpg");

		ArgumentCaptor<Execution> queued = ArgumentCaptor.captor();

		verify(executionEnqueueService, times(2)).enqueueOrExisting(queued.capture());

		Assertions.assertThat(queued.getAllValues()).extracting(Execution::getDedupKey).doesNotHaveDuplicates();
	}

	@Test
	void queuesAQuarantineWithTheQuarantineFolderAsItsOtherEnd(@TempDir Path folder, @TempDir Path quarantine)
			throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		when(quarantineFolderPolicy.root()).thenReturn(Optional.of(quarantine));

		launcher().quarantine(file);

		Execution queued = captureQueued();

		Assertions.assertThat(queued.getExecutionType()).isEqualTo(ExecutionType.EXPLORER_QUARANTINE);
		Assertions.assertThat(queued.getSourcePath()).isEqualTo(PathUtils.normalize(file));
		Assertions.assertThat(queued.getTargetPath()).isEqualTo(PathUtils.normalize(quarantine));
		Assertions.assertThat(queued.getDedupKey()).isEqualTo(OperationPathKey.canonical(file));
	}

	@Test
	void queuesADeleteKeyedOnThePathItDestroys(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		launcher().deletePermanently(file);

		Execution queued = captureQueued();

		Assertions.assertThat(queued.getExecutionType()).isEqualTo(ExecutionType.EXPLORER_DELETE);
		Assertions.assertThat(queued.getTargetPath()).isNull();
		Assertions.assertThat(queued.getDedupKey()).isEqualTo(OperationPathKey.canonical(file));
	}

	/**
	 * Finished inside the budget, so the answer is what happened - the same
	 * sentence the screen has always shown.
	 */
	@Test
	void answersWithTheOutcomeWhenTheWorkFinishesInTime(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		when(executionCompletionWait.awaitTerminal(anyLong(), any()))
				.thenReturn(Optional.of(finished(ExecutionStatus.FINISHED)));

		ExplorerActionResult result = launcher().rename(file, "holiday.jpg");

		Assertions.assertThat(result.success()).isTrue();
		Assertions.assertThat(result.pending()).isFalse();
		Assertions.assertThat(result.message()).isEqualTo(expected("backend.files.renameDone", "holiday.jpg"));
		Assertions.assertThat(result.processed()).isEqualTo(1);
	}

	/**
	 * Errors on the way through are not success, and the person is told what the
	 * run recorded rather than a sentence composed here.
	 */
	@Test
	void answersAFinishedWithErrorsRunAsUnsuccessful(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		when(executionCompletionWait.awaitTerminal(anyLong(), any()))
				.thenReturn(Optional.of(finished(ExecutionStatus.FINISHED_WITH_ERRORS)));

		Assertions.assertThat(launcher().rename(file, "holiday.jpg").success()).isFalse();
	}

	/**
	 * Past the budget with something alive to run it: accepted, still coming, and
	 * the screen is handed the execution to watch.
	 */
	@Test
	void answersThatTheWorkIsStillComingWhenTheBudgetRunsOut(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		when(executionCompletionWait.awaitTerminal(anyLong(), any())).thenReturn(Optional.empty());
		when(workerAvailability.current()).thenReturn(new WorkerAvailabilityResponse(true, 1, null));

		ExplorerActionResult result = launcher().rename(file, "holiday.jpg");

		Assertions.assertThat(result.pending()).isTrue();
		Assertions.assertThat(result.success()).isTrue();
		Assertions.assertThat(result.executionId()).isNotNull();
		Assertions.assertThat(result.message()).isEqualTo(expected("backend.files.stillProcessing"));
	}

	/**
	 * No worker alive is not a refusal and never a reason to do the work here: the
	 * request is durable, and the sentence says it is waiting for something to run
	 * it.
	 */
	@Test
	void acceptsTheCommandEvenWithNoWorkerAliveToRunIt(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		ExplorerCommandLauncher launcher = launcher();

		when(executionCompletionWait.awaitTerminal(anyLong(), any())).thenReturn(Optional.empty());
		when(workerAvailability.current()).thenReturn(new WorkerAvailabilityResponse(false, 0, null));

		ExplorerActionResult result = launcher.deletePermanently(file);

		Assertions.assertThat(result.pending()).isTrue();
		Assertions.assertThat(result.message()).isEqualTo(expected("backend.files.waitingForWorker"));
		Assertions.assertThat(file).exists();

		verify(executionEnqueueService).enqueueOrExisting(any());
	}

	private Execution captureQueued() {
		ArgumentCaptor<Execution> queued = ArgumentCaptor.captor();

		verify(executionEnqueueService).enqueueOrExisting(queued.capture());

		return queued.getValue();
	}

	/** What the enqueue gives back: the same request, now a row with an id. */
	private Execution queued(Execution request) {
		request.setId(1L);
		request.setExecutionPublicId(UUID.randomUUID());
		request.setStatus(ExecutionStatus.PENDING);

		return request;
	}

	private Execution finished(ExecutionStatus status) {
		return Execution.builder().id(1L).executionPublicId(UUID.randomUUID()).executionType(ExecutionType.EXPLORER_RENAME)
				.status(status).filesFound(1).filesAnalyzed(1).cacheHits(0).filesMoved(1).errors(0)
				.statusMessage(StatusMessage.coded("backend.files.renameDone", "[\"holiday.jpg\"]")).build();
	}

	private String expected(String key, Object... arguments) {
		return messages.getMessage(key, arguments, PT_BR);
	}

	private MessageSource messageSource() {
		ResourceBundleMessageSource source = new ResourceBundleMessageSource();

		source.setBasename("messages");
		source.setDefaultEncoding("UTF-8");
		source.setFallbackToSystemLocale(false);

		return source;
	}
}