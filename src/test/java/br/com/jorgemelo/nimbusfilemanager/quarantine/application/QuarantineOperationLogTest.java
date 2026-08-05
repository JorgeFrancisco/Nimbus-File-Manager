package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineMessages;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * What a quarantine operation says about itself when it ends.
 *
 * <p>
 * The row is written by the shared progress service, under the taking the
 * caller carries; what this class decides is the shape - which counter each
 * number lands in, and which status a run that stopped short deserves.
 */
class QuarantineOperationLogTest {

	private final ExecutionErrorService executionErrorService = mock(ExecutionErrorService.class);
	private final ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);
	private final QuarantineOperationLog log = new QuarantineOperationLog(executionErrorService,
			executionProgressService);

	private final ExecutionOwnership ownership = Takings.owning(5L);

	/**
	 * Items that stayed in quarantine waiting for a decision - a name collision, a
	 * missing origin folder - are not failures, so counting them as errors would
	 * make a restore that needs one click look broken.
	 */
	@Test
	void finishCountsPendingDecisionsApartFromFailures() {
		log.finish(ownership, 5, 3, 2, 0, QuarantineMessages.batchCompleted(0, 0, 0, 0));

		verify(executionProgressService).finishSelection(ownership, ExecutionStatus.FINISHED, 5,
				new ExecutionCounts(5, 3, 2, 0), QuarantineMessages.batchCompleted(0, 0, 0, 0));
	}

	@Test
	void finishFlagsTheExecutionWhenAFileFailed() {
		log.finish(ownership, 2, 1, 0, 1, QuarantineMessages.batchCompleted(0, 0, 0, 0));

		verify(executionProgressService).finishSelection(ownership, ExecutionStatus.FINISHED_WITH_ERRORS, 2,
				new ExecutionCounts(2, 1, 0, 1), QuarantineMessages.batchCompleted(0, 0, 0, 0));
	}

	/**
	 * A run that stopped before its last item reports how far it got, not the whole
	 * selection: the counters are what really happened, and the status says why it
	 * ended - which is the difference between "cancelei e parou" and "deu erro".
	 */
	@Test
	void stopClosesTheRowWithHowFarItGotAndWhyItEnded() {
		log.stop(ownership, ExecutionStatus.CANCELLED, 10, 3, 1, 0, QuarantineMessages.batchCancelled(3, 1, 0, 0));

		// Four of the ten were seen; claiming the whole selection was analysed would
		// make the history say it ran to the end.
		verify(executionProgressService).finishSelection(ownership, ExecutionStatus.CANCELLED, 10,
				new ExecutionCounts(4, 3, 1, 0), QuarantineMessages.batchCancelled(3, 1, 0, 0));
	}

	/**
	 * A row left with a null {@code finishedAt} is read as the operation currently
	 * running, so a crashed operation has to close its own row or it haunts every
	 * screen. The shared service does it in a transaction of its own, because the
	 * caller's may be what just broke.
	 */
	@Test
	void failClosesTheRowSoNoPhantomOperationIsLeftRunning() {
		log.fail(ownership, "disk gone");

		verify(executionProgressService).fail(ownership, ExecutionMessages.operationFailed("disk gone"));
	}

	@Test
	void recordFailureNamesTheFileThatFailed(@TempDir Path tmp) {
		Execution execution = mock(Execution.class);

		Path file = tmp.resolve("gone.jpg");

		log.recordFailure(execution, file, ExecutionErrorType.FILE_NOT_FOUND, "missing");

		verify(executionErrorService).save(file, ExecutionErrorType.FILE_NOT_FOUND, "missing", execution);
	}

	@Test
	void recordFailureLetsTheSharedClassifierNameAnException(@TempDir Path tmp) {
		Execution execution = mock(Execution.class);

		Path file = tmp.resolve("gone.jpg");

		RuntimeException failure = new IllegalStateException("boom");

		log.recordFailure(execution, file, failure);

		verify(executionErrorService).save(file, failure, execution);
	}
}