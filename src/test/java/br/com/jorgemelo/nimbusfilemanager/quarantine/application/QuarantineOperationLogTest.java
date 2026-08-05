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
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineMessages;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

class QuarantineOperationLogTest {

	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
	private final ExecutionErrorService executionErrorService = mock(ExecutionErrorService.class);
	private final ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);
	private final QuarantineOperationLog log = new QuarantineOperationLog(executionRepository, executionErrorService,
			executionProgressService, Clock.fixed(Instant.parse("2026-07-28T10:15:30Z"), ZoneOffset.UTC));

	@Test
	void finishCountsPendingDecisionsApartFromFailures() {
		Execution execution = managedExecution();

		log.finish(execution, 5, 3, 2, 0, QuarantineMessages.batchCompleted(0, 0, 0, 0));

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
		Assertions.assertThat(execution.getFilesMoved()).isEqualTo(3);
		Assertions.assertThat(execution.getCacheHits()).isEqualTo(2);
		Assertions.assertThat(execution.getErrors()).isZero();
		Assertions.assertThat(execution.getFinishedAt()).isNotNull();
	}

	@Test
	void finishFlagsTheExecutionWhenAFileFailed() {
		Execution execution = managedExecution();

		log.finish(execution, 2, 1, 0, 1, QuarantineMessages.batchCompleted(0, 0, 0, 0));

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FINISHED_WITH_ERRORS);
		Assertions.assertThat(execution.getErrors()).isEqualTo(1);
	}

	/**
	 * A run that stopped before its last item reports how far it got, not the whole
	 * selection: the counters are what really happened, and the status says why it
	 * ended - which is the difference between "cancelei e parou" and "deu erro".
	 */
	@Test
	void stopClosesTheRowWithHowFarItGotAndWhyItEnded() {
		Execution cancelled = managedExecution();

		log.stop(cancelled, ExecutionStatus.CANCELLED, 10, 3, 1, 0, QuarantineMessages.batchCancelled(3, 1, 0, 0));

		Assertions.assertThat(cancelled.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
		Assertions.assertThat(cancelled.getFilesFound()).isEqualTo(10);
		Assertions.assertThat(cancelled.getFilesMoved()).isEqualTo(3);
		Assertions.assertThat(cancelled.getFinishedAt()).isNotNull();

		// Four of the ten were seen; claiming the whole selection was analysed would
		// make the history say it ran to the end.
		Assertions.assertThat(cancelled.getFilesAnalyzed()).isEqualTo(4);
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