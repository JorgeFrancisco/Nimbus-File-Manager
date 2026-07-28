package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

class QuarantineOperationLogTest {

	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
	private final ExecutionErrorService executionErrorService = mock(ExecutionErrorService.class);
	private final ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);
	private final QuarantineOperationLog log = new QuarantineOperationLog(executionRepository, executionErrorService,
			executionProgressService, Clock.fixed(Instant.parse("2026-07-28T10:15:30Z"), ZoneOffset.UTC));

	@Test
	void startOpensAnExecutionOfItsOwnType() {
		when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		Execution execution = log.startRestore(3);

		Assertions.assertThat(execution.getExecutionType()).isEqualTo(ExecutionType.QUARANTINE_RESTORE);
		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.STARTED);
		Assertions.assertThat(execution.getFilesFound()).isEqualTo(3);
		Assertions.assertThat(execution.getFinishedAt()).isNull();
	}

	@Test
	void finishCountsPendingDecisionsApartFromFailures() {
		Execution execution = managedExecution();

		log.finish(execution, 5, 3, 2, 0, "done");

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
		Assertions.assertThat(execution.getFilesMoved()).isEqualTo(3);
		Assertions.assertThat(execution.getCacheHits()).isEqualTo(2);
		Assertions.assertThat(execution.getErrors()).isZero();
		Assertions.assertThat(execution.getFinishedAt()).isNotNull();
	}

	@Test
	void finishFlagsTheExecutionWhenAFileFailed() {
		Execution execution = managedExecution();

		log.finish(execution, 2, 1, 0, 1, "one failed");

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FINISHED_WITH_ERRORS);
		Assertions.assertThat(execution.getErrors()).isEqualTo(1);
	}

	/**
	 * Clearing missing records deletes nothing from disk, so it must not read on
	 * the executions screen as the purge that erases files.
	 */
	@Test
	void startAbsentCleanupIsToldApartFromThePurgeThatErasesFiles() {
		when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		Execution execution = log.startAbsentCleanup(4);

		Assertions.assertThat(execution.getExecutionType()).isEqualTo(ExecutionType.QUARANTINE_CLEANUP);
		Assertions.assertThat(execution.getFilesFound()).isEqualTo(4);
	}

	@Test
	void startPurgeOpensAnExecutionOfItsOwnType() {
		when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		Execution execution = log.startPurge(9);

		Assertions.assertThat(execution.getExecutionType()).isEqualTo(ExecutionType.QUARANTINE_PURGE);
		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.STARTED);
		Assertions.assertThat(execution.getFilesFound()).isEqualTo(9);
	}

	/**
	 * A row left with a null {@code finishedAt} is read as the operation currently
	 * running, so a crashed operation has to close its own row or it haunts every
	 * screen. The shared service does it in a transaction of its own, because the
	 * caller's may be what just broke.
	 */
	@Test
	void failClosesTheRowSoNoPhantomOperationIsLeftRunning() {
		Execution execution = mock(Execution.class);

		log.fail(execution, "disk gone");

		verify(executionProgressService).fail(execution, ExecutionMessages.operationFailed("disk gone"));
	}

	@Test
	void recordFailureNamesTheFileThatFailed() {
		Execution execution = mock(Execution.class);

		Path file = Path.of("D:", "trash", "10__a.jpg");

		log.recordFailure(execution, file, ExecutionErrorType.FILE_NOT_FOUND, "gone");

		verify(executionErrorService).save(file, ExecutionErrorType.FILE_NOT_FOUND, "gone", execution);
	}

	@Test
	void recordFailureHandsTheExceptionToTheSharedClassifier() {
		Execution execution = mock(Execution.class);

		Path file = Path.of("D:", "trash", "10__a.jpg");

		IOException failure = new IOException("disk gone");

		log.recordFailure(execution, file, failure);

		verify(executionErrorService).save(file, failure, execution);
	}

	private Execution managedExecution() {
		Execution execution = Execution.builder().id(7L).filesMoved(0).build();

		when(executionRepository.findById(7L)).thenReturn(Optional.of(execution));

		when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		return execution;
	}
}