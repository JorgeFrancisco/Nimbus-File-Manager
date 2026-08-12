package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.ConversionExecutionRecorder;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.constants.ConversionMessages;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionTotals;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionPossession;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.ExecutionStepRepository;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantineOperationLog;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineMessages;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * Two takings of one execution, alive in one process at the same time.
 *
 * <p>
 * The shape recovery produces out of a worker that only looked dead: attempt N
 * paused long enough for its lease to lapse, the row went back on the queue, the
 * <em>same</em> worker claimed it again as N+1, and N woke up still holding
 * everything it needs to write. The worker name is identical on both sides, so
 * the attempt number is the whole of the difference - which is why every write
 * here names the taking rather than the row.
 *
 * <p>
 * Against a real database, and deliberately not {@code @Transactional}: what has
 * to be true is that N's writes are absent from the committed row, and a test
 * sharing a transaction with a {@code REQUIRES_NEW} boundary could not see that.
 * Each assertion re-reads the row.
 */
@SpringBootTest
@Testcontainers
class ReplacedTakingIntegrationTest {

	private static final String WORKER = "worker-that-came-back";

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

	@Autowired
	private ExecutionProgressService executionProgressService;

	@Autowired
	private ExecutionOwnershipGuard executionOwnershipGuard;

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private ExecutionStepRepository executionStepRepository;

	@Autowired
	private QuarantineOperationLog quarantineOperationLog;

	@Autowired
	private ConversionExecutionRecorder conversionExecutionRecorder;

	private long executionId;

	private ExecutionOwnership attemptN;
	private ExecutionOwnership attemptNext;

	@BeforeEach
	void twoTakingsOfOneRow() {
		executionStepRepository.deleteAll();
		executionRepository.deleteAll();

		executionId = executionRepository.saveAndFlush(running()).getId();

		attemptN = Takings.taking(executionId, 1, executionOwnershipGuard);

		executionOwnershipGuard.takes(new ExecutionPossession(executionId, WORKER, 1));
	}

	/**
	 * While N is the taking, N writes. Half of the proof, and the half that keeps
	 * the other half honest: a guard that refused everything would pass the rest of
	 * this class while breaking the product.
	 */
	@Test
	void theTakingThatIsStillCurrentGoesOnWriting() {
		executionProgressService.updateTotal(attemptN, 500);
		executionProgressService.updatePhase(attemptN, ExecutionPhase.PROCESSING,
				ExecutionStepType.PROCESSING_STARTED, ExecutionMessages.processingFiles());

		Execution row = reload();

		assertThat(row.getTotalExpected()).isEqualTo(500);
		assertThat(row.getPhase()).isEqualTo(ExecutionPhase.PROCESSING);
	}

	/**
	 * Every progress write N could still make, once N+1 has begun. None of them
	 * reaches the row, and none of them leaves a step behind either - a history
	 * written by a taking that is over is as misleading as the row would be.
	 */
	@Test
	void noProgressFromTheReplacedTakingReachesTheRow() {
		replacedByTheNextAttempt();

		executionProgressService.updateTotal(attemptN, 999);
		executionProgressService.updatePhase(attemptN, ExecutionPhase.SCANNING, ExecutionStepType.STARTED,
				ExecutionMessages.processingFiles());
		executionProgressService.updateProgress(attemptN, 9, 9, 9, 9, Path.of("late.jpg"));
		executionProgressService.updateProgress(attemptN, 9, 9, 9, 9, "late");
		executionProgressService.updateLiveProgress(attemptN, 9, 9, 9, 9, ExecutionMessages.progressUpdated());
		executionProgressService.updateCurrentItem(attemptN, 80);
		executionProgressService.startsCurrentItem(attemptN);

		Execution row = reload();

		assertThat(row.getTotalExpected()).isNull();
		assertThat(row.getPhase()).isNull();
		assertThat(row.getFilesFound()).isZero();
		assertThat(row.getFilesAnalyzed()).isZero();
		assertThat(row.getCacheHits()).isZero();
		assertThat(row.getErrors()).isZero();
		assertThat(row.getCurrentItemPercent()).isEqualTo(40);
		assertThat(row.getStatusMessage()).isNull();

		assertThat(executionStepRepository.findByExecutionIdOrderByCreatedAtAsc(executionId)).isEmpty();
	}

	/**
	 * And no ending either. An execution the successor is still running must not be
	 * closed by the taking it replaced - which is the write that would be read as
	 * "finished" on every screen while the work went on.
	 */
	@Test
	void noEndingFromTheReplacedTakingReachesTheRow() {
		replacedByTheNextAttempt();

		executionProgressService.finish(attemptN, ExecutionStatus.FINISHED, 9, 9, 9, 9,
				ExecutionMessages.inventoryCompleted());
		executionProgressService.fail(attemptN, ExecutionMessages.inventoryFailed("too late"));
		executionProgressService.cancel(attemptN, ExecutionMessages.inventoryCancelled());
		executionProgressService.interrupt(attemptN, ExecutionMessages.executionInterrupted());
		executionProgressService.reject(attemptN, ExecutionMessages.executionSuperseded());
		executionProgressService.finishReconcile(attemptN, 9, 9, ExecutionMessages.reconcileRepaired(9, 9, 9));
		executionProgressService.finishCommand(attemptN, ExecutionStatus.FINISHED, new ExecutionCounts(9, 9, 9, 9),
				ExecutionMessages.inventoryCompleted());
		executionProgressService.finishSelection(attemptN, ExecutionStatus.FINISHED, 9,
				new ExecutionCounts(9, 9, 9, 9), ExecutionMessages.inventoryCompleted());

		Execution row = reload();

		assertThat(row.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
		assertThat(row.getFinishedAt()).isNull();

		assertThat(executionStepRepository.findByExecutionIdOrderByCreatedAtAsc(executionId)).isEmpty();
	}

	/**
	 * The finalisers that used to close the row themselves, now that they go
	 * through the same door as everything else. They are the ones the door was
	 * built for: each one loaded the row, changed it and saved it, so nothing but
	 * the taking they carry stands between a late finish and the run underneath it.
	 */
	@Test
	void noEndingFromTheReplacedTakingReachesTheRowThroughADirectFinaliser() {
		replacedByTheNextAttempt();

		quarantineOperationLog.finish(attemptN, 9, 9, 0, 0, QuarantineMessages.batchCompleted(9, 0, 0, 0));
		quarantineOperationLog.stop(attemptN, ExecutionStatus.CANCELLED, 9, 3, 0, 0,
				QuarantineMessages.batchCancelled(3, 0, 0, 0));
		quarantineOperationLog.fail(attemptN, "too late");

		conversionExecutionRecorder.finish(attemptN, new ConversionTotals(9, 9, 0, 0, 10, 4, 6),
				ConversionMessages.completed(9, 0, 0, "6 B"), false);
		conversionExecutionRecorder.fail(attemptN, "too late");

		Execution row = reload();

		assertThat(row.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
		assertThat(row.getFinishedAt()).isNull();
		assertThat(row.getFilesMoved()).isZero();

		assertThat(executionStepRepository.findByExecutionIdOrderByCreatedAtAsc(executionId)).isEmpty();
	}

	/**
	 * And the successor is unaffected by any of it. Refusing the replaced taking is
	 * only half of what the rule promises; the other half is that the run which
	 * took over goes on exactly as it would have.
	 */
	@Test
	void theTakingThatReplacedItGoesOnWorkingNormally() {
		replacedByTheNextAttempt();

		executionProgressService.finish(attemptN, ExecutionStatus.ERROR, 9, 9, 9, 9,
				ExecutionMessages.inventoryFailed("too late"));

		executionProgressService.updateTotal(attemptNext, 120);
		executionProgressService.updateProgress(attemptNext, 120, 118, 2, 0, Path.of("photo.jpg"));
		executionProgressService.finish(attemptNext, ExecutionStatus.FINISHED, 120, 118, 2, 0,
				ExecutionMessages.inventoryCompleted());

		Execution row = reload();

		assertThat(row.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
		assertThat(row.getTotalExpected()).isEqualTo(120);
		assertThat(row.getFilesFound()).isEqualTo(120);
		assertThat(row.getFilesAnalyzed()).isEqualTo(118);
		assertThat(row.getFinishedAt()).isNotNull();

		assertThat(executionStepRepository.findByExecutionIdOrderByCreatedAtAsc(executionId)).isNotEmpty();
	}

	/**
	 * Recovery gave the row back and the same worker took it again. Only the
	 * attempt number separates the two, which is the case a worker name alone
	 * cannot answer.
	 */
	private void replacedByTheNextAttempt() {
		attemptNext = Takings.taking(executionId, 2, executionOwnershipGuard);

		executionOwnershipGuard.takes(new ExecutionPossession(executionId, WORKER, 2));
	}

	private Execution reload() {
		return executionRepository.findById(executionId).orElseThrow();
	}

	private Execution running() {
		return Execution.builder().executionType(ExecutionType.INVENTORY).status(ExecutionStatus.RUNNING)
				.claimedBy(WORKER).claimCount(1).currentItemPercent(40).recursive(false).executeFlag(true)
				.filesFound(0).filesAnalyzed(0).cacheHits(0).errors(0).build();
	}
}