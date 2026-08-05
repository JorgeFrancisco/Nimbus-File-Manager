package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateDeletionProgress;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * What the duplicates screen reads once the moving happens somewhere else. All
 * of it comes from the row: a file either moved or it did not, and none of them
 * takes long enough to need anything finer.
 */
class DuplicateDeletionProgressServiceTest {

	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);

	@Test
	void reportsNothingRunningWhenNoDeletionHasEverBeenAskedFor() {
		when(executionRepository.findFirstByExecutionTypeOrderByCreatedAtDesc(ExecutionType.DEDUP_DELETE))
				.thenReturn(Optional.empty());

		DuplicateDeletionProgress progress = service().snapshot();

		Assertions.assertThat(progress.running()).isFalse();
		Assertions.assertThat(progress.result()).isNull();
	}

	@Test
	void countsWhatIsDoneWhileTheMoveIsStillGoing() {
		latest(ExecutionStatus.RUNNING, 4, 1);

		DuplicateDeletionProgress progress = service().snapshot();

		Assertions.assertThat(progress.running()).isTrue();
		Assertions.assertThat(progress.processed()).isEqualTo(1);
		Assertions.assertThat(progress.total()).isEqualTo(4);
		Assertions.assertThat(progress.percent()).isEqualTo(25);
		Assertions.assertThat(progress.result()).isNull();
	}

	/**
	 * The final report is those same counters with the message beside them - there
	 * was never anything else in it.
	 */
	@Test
	void reportsTheCountersAsTheFinalResultOnceItHasEnded() {
		Execution finished = latest(ExecutionStatus.FINISHED, 3, 3);

		finished.setFilesMoved(2);
		finished.setCacheHits(1);
		finished.setErrors(0);

		DuplicateDeletionProgress progress = service().snapshot();

		Assertions.assertThat(progress.running()).isFalse();
		Assertions.assertThat(progress.result().moved()).isEqualTo(2);
		Assertions.assertThat(progress.result().skipped()).isEqualTo(1);
		Assertions.assertThat(progress.result().errors()).isZero();
		Assertions.assertThat(progress.result().message()).isEqualTo("done");
	}

	/**
	 * The screen asks for the running row before it offers the button at all, and
	 * a batch that ended is not one to follow.
	 */
	@Test
	void offersTheRunningBatchAndNothingOnceItHasEnded() {
		latest(ExecutionStatus.RUNNING, 4, 1);

		Assertions.assertThat(service().active()).isPresent();

		latest(ExecutionStatus.FINISHED, 4, 4);

		Assertions.assertThat(service().active()).isEmpty();
	}

	/**
	 * A row queued before anything was counted has no total of its own yet, so the
	 * number the request asked for is what the bar divides by.
	 */
	@Test
	void fallsBackToWhatWasAskedForWhileNoTotalHasBeenCounted() {
		Execution queued = Execution.builder().id(5L).publicId(UUID.randomUUID())
				.executionType(ExecutionType.DEDUP_DELETE).status(ExecutionStatus.RUNNING).filesFound(6)
				.filesAnalyzed(3).build();

		when(executionRepository.findFirstByExecutionTypeOrderByCreatedAtDesc(ExecutionType.DEDUP_DELETE))
				.thenReturn(Optional.of(queued));

		DuplicateDeletionProgress progress = service().snapshot();

		Assertions.assertThat(progress.total()).isEqualTo(6);
		Assertions.assertThat(progress.percent()).isEqualTo(50);
	}

	/** A row that ended without a message reports counters and nothing else. */
	@Test
	void reportsTheCountersEvenWhenNoSentenceWasWritten() {
		Execution finished = latest(ExecutionStatus.FINISHED, 1, 1);

		finished.setFilesMoved(1);
		finished.setCacheHits(0);
		finished.setErrors(0);
		finished.setStatusMessage(null);

		Assertions.assertThat(service().snapshot().result().message()).isNull();
	}

	private Execution latest(ExecutionStatus status, int total, int processed) {
		Execution execution = Execution.builder().id(5L).publicId(UUID.randomUUID())
				.executionType(ExecutionType.DEDUP_DELETE).status(status).totalExpected(total).filesAnalyzed(processed)
				.statusMessage(StatusMessage.raw("done")).build();

		when(executionRepository.findFirstByExecutionTypeOrderByCreatedAtDesc(ExecutionType.DEDUP_DELETE))
				.thenReturn(Optional.of(execution));

		return execution;
	}

	private DuplicateDeletionProgressService service() {
		return new DuplicateDeletionProgressService(executionRepository);
	}
}