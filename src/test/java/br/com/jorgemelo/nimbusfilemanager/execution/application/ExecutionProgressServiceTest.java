package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionPossession;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.ExecutionStepRepository;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueue;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

@ExtendWith(MockitoExtension.class)
class ExecutionProgressServiceTest {

	private static final ExecutionMessage LATE = ExecutionMessages.operationFailed("too late");

	@Mock
	private ExecutionRepository executionRepository;

	@Mock
	private ExecutionStepRepository executionStepRepository;

	private final ExecutionMessageCodec messageCodec = new ExecutionMessageCodec(new ObjectMapper());

	/**
	 * Reconcile fills different counters from every other type: it analyses
	 * nothing, hits no cache and moves no file, so what it can honestly report is
	 * what the walk found and how many entries it corrected.
	 */
	@Test
	void finishReconcileShouldRecordWhatWasFoundAndRepaired() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().finishReconcile(Takings.owning(1L), 120, 3, ExecutionMessages.reconcileRepaired(1, 1, 1));

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
		Assertions.assertThat(execution.getFilesFound()).isEqualTo(120);
		Assertions.assertThat(execution.getRepairedItems()).isEqualTo(3);
		Assertions.assertThat(execution.getFinishedAt()).isNotNull();

		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.FINISHED));
	}

	@Test
	void finishReconcileShouldRecordZeroWhenNothingWasRepaired() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().finishReconcile(Takings.owning(1L), 120, 0, ExecutionMessages.reconcileRepaired(0, 0, 0));

		Assertions.assertThat(execution.getRepairedItems()).isZero();
		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
	}

	/**
	 * A command that acted on a set of items has to say what became of each of
	 * them, and the counter the general {@code finish} cannot write is the one that
	 * matters most here: how many it actually carried out. A deletion of forty
	 * files reporting none moved is a row whose numbers say the opposite of what
	 * happened.
	 */
	@Test
	void finishCommandShouldRecordWhatWasMovedKeptAndFailed() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().finishCommand(Takings.owning(1L), ExecutionStatus.FINISHED_WITH_ERRORS,
				new ExecutionCounts(9, 6, 2, 1), ExecutionMessages.operationFailed("parcial"));

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FINISHED_WITH_ERRORS);
		Assertions.assertThat(execution.getFilesFound()).isEqualTo(9);
		Assertions.assertThat(execution.getFilesAnalyzed()).isEqualTo(9);
		Assertions.assertThat(execution.getFilesMoved()).isEqualTo(6);
		Assertions.assertThat(execution.getCacheHits()).isEqualTo(2);
		Assertions.assertThat(execution.getErrors()).isEqualTo(1);
		Assertions.assertThat(execution.getFinishedAt()).isNotNull();

		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.FINISHED));
	}

	/**
	 * A run over a selection that stopped early has two different numbers to
	 * report, and writing the selection into both columns would say it saw every
	 * item when it saw four of them.
	 */
	@Test
	void finishSelectionKeepsHowFarItGotApartFromHowMuchWasSelected() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().finishSelection(Takings.owning(1L), ExecutionStatus.INTERRUPTED, 40, new ExecutionCounts(4, 3, 1, 0),
				ExecutionMessages.executionInterrupted());

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.INTERRUPTED);
		Assertions.assertThat(execution.getFilesFound()).isEqualTo(40);
		Assertions.assertThat(execution.getFilesAnalyzed()).isEqualTo(4);
		Assertions.assertThat(execution.getFilesMoved()).isEqualTo(3);
		Assertions.assertThat(execution.getCacheHits()).isEqualTo(1);
		Assertions.assertThat(execution.getErrors()).isZero();
		Assertions.assertThat(execution.getFinishedAt()).isNotNull();

		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.FINISHED));
	}

	@Test
	void updatePhaseShouldUpdateManagedExecutionAndPersistStep() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().updatePhase(Takings.owning(1L), ExecutionPhase.PROCESSING, ExecutionStepType.PROCESSING_STARTED,
				ExecutionMessages.processingFiles());

		Assertions.assertThat(execution.getPhase()).isEqualTo(ExecutionPhase.PROCESSING);
		Assertions.assertThat(execution.getStatusMessage().getCode()).isEqualTo(ExecutionMessages.PROCESSING_FILES);
		Assertions.assertThat(execution.getStatusMessage().getText()).isNull();

		verify(executionRepository, never()).save(any());
		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.PROCESSING_STARTED
				&& ExecutionMessages.PROCESSING_FILES.equals(step.getStatusMessage().getCode())));
	}

	@Test
	void cancelShouldMarkExecutionCancelledWithFinishTimeAndMessage() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().cancel(Takings.owning(1L), ExecutionMessages.inventoryCancelled());

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
		Assertions.assertThat(execution.getFinishedAt()).isNotNull();
		Assertions.assertThat(execution.getStatusMessage().getCode()).isEqualTo(ExecutionMessages.INVENTORY_CANCELLED);

		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.CANCELLED));
	}

	@Test
	void failShouldMarkExecutionErroredWithFinishTimeAndMessage() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().fail(Takings.owning(1L), ExecutionMessages.inventoryFailed("boom"));

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.ERROR);
		Assertions.assertThat(execution.getFinishedAt()).isNotNull();
		Assertions.assertThat(execution.getStatusMessage().getCode()).isEqualTo(ExecutionMessages.INVENTORY_FAILED);
		Assertions.assertThat(execution.getStatusMessage().getArgs()).contains("boom");

		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.ERROR));
	}

	@Test
	void updateProgressShouldStoreCountersAndPaths() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().updateProgress(Takings.owning(1L), 2, 1, 1, 0, Path.of("C:/input/photo.jpg"));

		Assertions.assertThat(execution.getFilesFound()).isEqualTo(2);
		Assertions.assertThat(execution.getCacheHits()).isEqualTo(1);
		Assertions.assertThat(execution.getStatusMessage().getCode()).isEqualTo(ExecutionMessages.PROCESSING_FILE);

		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.PROGRESS_UPDATED
				&& step.getPath().endsWith(Path.of("input", "photo.jpg").toString())));
	}

	@Test
	void updateProgressWithoutAFileFallsBackToTheProgressUpdatedCode() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().updateProgress(Takings.owning(1L), 4, 2, 1, 0, (Path) null);

		Assertions.assertThat(execution.getFilesFound()).isEqualTo(4);
		Assertions.assertThat(execution.getStatusMessage().getCode()).isEqualTo(ExecutionMessages.PROGRESS_UPDATED);
		Assertions.assertThat(execution.getStatusMessage().getArgs()).isNull();

		verify(executionStepRepository)
				.save(argThat(step -> step.getStepType() == ExecutionStepType.PROGRESS_UPDATED && step.getPath() == null
						&& ExecutionMessages.PROGRESS_UPDATED.equals(step.getStatusMessage().getCode())));
	}

	@Test
	void updateLiveProgressSetsCountersAndMessageWithoutRecordingAStep() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().updateLiveProgress(Takings.owning(1L), 25, 10, 3, 1, ExecutionMessages.extractingMetadata());

		Assertions.assertThat(execution.getFilesFound()).isEqualTo(25);
		Assertions.assertThat(execution.getFilesAnalyzed()).isEqualTo(10);
		Assertions.assertThat(execution.getStatusMessage().getCode()).isEqualTo(ExecutionMessages.EXTRACTING_METADATA);

		verify(executionStepRepository, never()).save(any());
	}

	@Test
	void updateTotalShouldStoreTotalExpected() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().updateTotal(Takings.owning(1L), 150000);

		Assertions.assertThat(execution.getTotalExpected()).isEqualTo(150000);
	}

	@Test
	void updateProgressWithTextShouldStoreCountersAndMessageWithoutAPath() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().updateProgress(Takings.owning(1L), 10, 6, 3, 1, "C:/input/current-item.jpg");
		service().updateProgress(Takings.owning(1L), 12, 7, 3, 1, (String) null);

		Assertions.assertThat(execution.getFilesFound()).isEqualTo(12);
		Assertions.assertThat(execution.getFilesAnalyzed()).isEqualTo(7);

		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.PROGRESS_UPDATED
				&& step.getPath() == null && ExecutionMessages.PROCESSING_ITEM.equals(step.getStatusMessage().getCode())
				&& step.getStatusMessage().getArgs().contains("C:/input/current-item.jpg")));
		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.PROGRESS_UPDATED
				&& ExecutionMessages.PROGRESS_UPDATED.equals(step.getStatusMessage().getCode())));
	}

	@Test
	void finishFailAndInterruptedExecutionsShouldUpdateStatus() {
		Execution execution = execution(1L);
		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().finish(Takings.owning(1L), ExecutionStatus.FINISHED, 5, 4, 1, 0,
				ExecutionMessages.inventoryCompleted());

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
		Assertions.assertThat(execution.getFinishedAt()).isNotNull();

		service().fail(Takings.owning(1L), ExecutionMessages.inventoryFailed("failed"));

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.ERROR);

		service().cancel(Takings.owning(1L), ExecutionMessages.inventoryCancelled());

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
		Assertions.assertThat(execution.getFinishedAt()).isNotNull();

		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.CANCELLED
				&& ExecutionMessages.INVENTORY_CANCELLED.equals(step.getStatusMessage().getCode())));

		service().interrupt(Takings.owning(1L), ExecutionMessages.executionInterrupted());

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.INTERRUPTED);

		verify(executionRepository, never()).save(any());
		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.INTERRUPTED));
	}

	@Test
	void shouldThrowWhenExecutionCannotBeFound() {
		when(executionRepository.findById(99L)).thenReturn(Optional.empty());

		assertThatIllegalStateException()
				.isThrownBy(() -> service().updatePhase(Takings.owning(99L), ExecutionPhase.PROCESSING,
						ExecutionStepType.ERROR, ExecutionMessages.inventoryFailed("missing")))
				.withMessage("Execution not found: 99");
	}

	/**
	 * Two endings that are not failures and must not be told apart by reading a
	 * sentence: an execution whose locks went away stopped through no fault of the
	 * work, and one the product refused never ran at all. Somebody reading the
	 * history to ask "did my cancel work?" has to be able to tell all three apart
	 * by status alone.
	 */
	@Test
	void interruptedAndRejectedEndingsCarryTheirOwnStatusAndStep() {
		Execution interrupted = execution(1L);
		Execution rejected = execution(2L);

		interrupted.setCurrentItemPercent(40);
		rejected.setCurrentItemPercent(70);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(interrupted));
		when(executionRepository.findById(2L)).thenReturn(Optional.of(rejected));

		service().interrupt(Takings.owning(1L), ExecutionMessages.executionInterrupted());

		Assertions.assertThat(interrupted.getStatus()).isEqualTo(ExecutionStatus.INTERRUPTED);
		Assertions.assertThat(interrupted.getFinishedAt()).isNotNull();

		// Whatever the run was halfway through is over: leaving the column set would
		// show the old item still advancing on every screen that reads the row.
		Assertions.assertThat(interrupted.getCurrentItemPercent()).isNull();

		service().reject(Takings.owning(2L), ExecutionMessages.executionSuperseded());

		Assertions.assertThat(rejected.getStatus()).isEqualTo(ExecutionStatus.REJECTED);
		Assertions.assertThat(rejected.getFinishedAt()).isNotNull();
		Assertions.assertThat(rejected.getCurrentItemPercent()).isNull();

		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.INTERRUPTED));
		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.REJECTED));
	}

	/**
	 * Every write this class offers, made under a taking that a later one of the
	 * same row has replaced, and none of them lands.
	 *
	 * <p>
	 * One test rather than fifteen because the rule is one rule: what is being
	 * asserted is that no way in is left open, and a list of near-identical tests
	 * would say the same thing while making it easier to add a sixteenth method
	 * and forget it. The row is compared with itself, so a write that changed
	 * anything at all - a counter, a phase, a status, a message - fails this.
	 */
	@Test
	void noWriteReachesTheRowOnceItsTakingHasBeenReplaced() {
		ExecutionOwnershipGuard guard = new ExecutionOwnershipGuard(mock(ExecutionQueue.class));

		Execution row = execution(31L);

		LocalDateTime startedAt = row.getStartedAt();

		ExecutionOwnership replaced = Takings.taking(31L, 1, guard);

		// The row was recovered and taken again, in this same process and by the same
		// name: only the attempt number separates the two.
		guard.takes(new ExecutionPossession(31L, "worker-that-lost-it", 2));

		ExecutionProgressService lostOwner = service();

		lostOwner.updatePhase(replaced, ExecutionPhase.SCANNING, ExecutionStepType.STARTED, LATE);
		lostOwner.updateProgress(replaced, 9, 9, 9, 9, Path.of("late.jpg"));
		lostOwner.updateProgress(replaced, 9, 9, 9, 9, "late");
		lostOwner.updateLiveProgress(replaced, 9, 9, 9, 9, LATE);
		lostOwner.updateTotal(replaced, 999);
		lostOwner.updateCurrentItem(replaced, 50);
		lostOwner.startsCurrentItem(replaced);
		lostOwner.finish(replaced, ExecutionStatus.FINISHED, 9, 9, 9, 9, LATE);
		lostOwner.finishReconcile(replaced, 9, 9, LATE);
		lostOwner.finishCommand(replaced, ExecutionStatus.FINISHED, new ExecutionCounts(9, 9, 9, 9), LATE);
		lostOwner.finishSelection(replaced, ExecutionStatus.FINISHED, 9, new ExecutionCounts(9, 9, 9, 9), LATE);
		lostOwner.cancel(replaced, LATE);
		lostOwner.interrupt(replaced, LATE);
		lostOwner.reject(replaced, LATE);
		lostOwner.fail(replaced, LATE);

		Assertions.assertThat(row.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
		Assertions.assertThat(row.getFinishedAt()).isNull();
		Assertions.assertThat(row.getPhase()).isNull();
		Assertions.assertThat(row.getTotalExpected()).isNull();
		Assertions.assertThat(row.getStatusMessage()).isNull();
		Assertions.assertThat(row.getStartedAt()).isEqualTo(startedAt);
		Assertions.assertThat(row.getFilesFound()).isZero();
		Assertions.assertThat(row.getFilesAnalyzed()).isZero();
		Assertions.assertThat(row.getCacheHits()).isZero();
		Assertions.assertThat(row.getErrors()).isZero();

		verify(executionRepository, never()).findById(31L);
		verify(executionStepRepository, never()).save(any());
		verify(executionRepository, never()).save(any());
	}

	private ExecutionProgressService service() {
		return new ExecutionProgressService(mock(ExecutionQueue.class), executionRepository,
				new ExecutionItemProgressWriter(executionRepository), executionStepRepository, messageCodec,
				Clock.systemDefaultZone());
	}

	private Execution execution(Long id) {
		return Execution.builder().id(id).executionType(ExecutionType.INVENTORY).status(ExecutionStatus.RUNNING)
				.startedAt(LocalDateTime.now()).filesFound(0).filesAnalyzed(0).cacheHits(0).errors(0).build();
	}
}