package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.Progress;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineProgress;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ExecutionLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * What the quarantine screen polls now that the batches happen elsewhere. Every
 * answer comes from the row, because there is no longer a response to wait on.
 */
class QuarantineProgressServiceTest {

	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);

	@Test
	void reportsNothingRunningWhenNeitherBatchWasEverAskedFor() {
		latest(ExecutionType.QUARANTINE_RESTORE, null);
		latest(ExecutionType.QUARANTINE_PURGE, null);

		QuarantineProgress progress = service().snapshot();

		Assertions.assertThat(progress.running()).isFalse();
		Assertions.assertThat(progress.executionType()).isNull();
		Assertions.assertThat(progress.message()).isNull();
	}

	@Test
	void countsWhatIsDoneWhileTheBatchIsStillGoing() {
		latest(ExecutionType.QUARANTINE_RESTORE,
				row(ExecutionType.QUARANTINE_RESTORE, ExecutionStatus.RUNNING, LocalDateTime.now(), 4, 1, 0, 0));
		latest(ExecutionType.QUARANTINE_PURGE, null);

		QuarantineProgress progress = service().snapshot();

		Assertions.assertThat(progress.running()).isTrue();
		Assertions.assertThat(progress.executionType()).isEqualTo(ExecutionType.QUARANTINE_RESTORE.name());
		Assertions.assertThat(progress.total()).isEqualTo(4);
		Assertions.assertThat(progress.succeeded()).isEqualTo(1);
	}

	/**
	 * The screen has one status line, and restoring, purging and clearing absent
	 * records never overlap in what the person is doing - so whichever was asked
	 * for last is the one they are waiting on.
	 */
	@Test
	void answersWithWhicheverWasAskedForLast() {
		latest(ExecutionType.QUARANTINE_RESTORE, row(ExecutionType.QUARANTINE_RESTORE, ExecutionStatus.FINISHED,
				LocalDateTime.now().minusMinutes(10), 3, 3, 0, 0));
		latest(ExecutionType.QUARANTINE_PURGE,
				row(ExecutionType.QUARANTINE_PURGE, ExecutionStatus.FINISHED, LocalDateTime.now(), 2, 2, 0, 0));

		QuarantineProgress progress = service().snapshot();

		Assertions.assertThat(progress.executionType()).isEqualTo(ExecutionType.QUARANTINE_PURGE.name());
		Assertions.assertThat(progress.running()).isFalse();
	}

	/**
	 * Clearing absent records became a request like the others, so the screen has
	 * to be able to follow it: it polls one status line, and a cleanup left out of
	 * the question would show the previous batch as if it were the answer.
	 */
	@Test
	void followsTheAbsentRecordCleanupToo() {
		latest(ExecutionType.QUARANTINE_RESTORE, row(ExecutionType.QUARANTINE_RESTORE, ExecutionStatus.FINISHED,
				LocalDateTime.now().minusMinutes(10), 3, 3, 0, 0));
		latest(ExecutionType.QUARANTINE_CLEANUP,
				row(ExecutionType.QUARANTINE_CLEANUP, ExecutionStatus.RUNNING, LocalDateTime.now(), 5, 2, 0, 0));

		QuarantineProgress progress = service().snapshot();

		Assertions.assertThat(progress.executionType()).isEqualTo(ExecutionType.QUARANTINE_CLEANUP.name());
		Assertions.assertThat(progress.running()).isTrue();
		Assertions.assertThat(progress.total()).isEqualTo(5);
	}

	/** A cancelled batch is over, whatever it managed to do before stopping. */
	@Test
	void readsACancelledBatchAsNoLongerRunning() {
		latest(ExecutionType.QUARANTINE_RESTORE,
				row(ExecutionType.QUARANTINE_RESTORE, ExecutionStatus.CANCELLED, LocalDateTime.now(), 9, 2, 0, 0));
		latest(ExecutionType.QUARANTINE_PURGE, null);

		QuarantineProgress progress = service().snapshot();

		Assertions.assertThat(progress.running()).isFalse();
		Assertions.assertThat(progress.succeeded()).isEqualTo(2);
	}

	/**
	 * A request that was never made is not the previous batch.
	 *
	 * <p>
	 * The screen polls one status line, so answering a refusal with the snapshot of
	 * whatever ran last would show somebody their earlier purge as though it were
	 * what they had just asked for - the one answer worse than none. What comes
	 * back instead is nothing running, no type, and the reason.
	 */
	@Test
	void reportsARefusedRequestAsNothingRunningAndSaysWhy() {
		QuarantineProgress progress = service().refused("the quarantine folder is not configured");

		Assertions.assertThat(progress.running()).isFalse();
		Assertions.assertThat(progress.executionType()).isNull();
		Assertions.assertThat(progress.total()).isZero();
		Assertions.assertThat(progress.succeeded()).isZero();
		Assertions.assertThat(progress.message()).isEqualTo("the quarantine folder is not configured");
	}

	private void latest(ExecutionType executionType, Execution execution) {
		when(executionRepository.findFirstByExecutionTypeOrderByCreatedAtDesc(executionType))
				.thenReturn(Optional.ofNullable(execution));
	}

	private Execution row(ExecutionType executionType, ExecutionStatus status, LocalDateTime createdAt, int total,
			int succeeded, int skipped, int errors) {
		return Execution.builder().id(5L).executionPublicId(UUID.randomUUID()).executionType(executionType).status(status)
				.createdAt(createdAt).filesFound(total).filesMoved(succeeded).cacheHits(skipped).errors(errors)
				.statusMessage(StatusMessage.raw("done")).build();
	}

	private QuarantineProgressService service() {
		return new QuarantineProgressService(executionRepository,
				new ExecutionMapper(new ExecutionMessageCodec(new ObjectMapper()), mock(ExecutionLabels.class),
						Progress.reader(), Progress.estimator()));
	}
}