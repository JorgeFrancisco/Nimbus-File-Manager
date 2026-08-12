package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.EtaState;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Progress;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.model.MetadataRebuildPreviewItemRecord;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.model.MetadataRebuildPreviewRecord;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.repository.MetadataRebuildPreviewItemRepository;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.repository.MetadataRebuildPreviewRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.application.DateSourceLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * Where the settings panel's three questions come from now.
 *
 * <p>
 * All three used to be fields of a runner, which answered only while the pass
 * happened in the process being asked. Reading them from the row is what makes
 * them survive a restart of either side, and what lets the panel report a run
 * this application never started.
 */
class MetadataRunReaderTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.MAY, 1, 10, 0);

	private static final String FOLDER = "D:\\photos";

	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
	private final MetadataRebuildPreviewRepository previewRepository = mock(MetadataRebuildPreviewRepository.class);
	private final MetadataRebuildPreviewItemRepository itemRepository = mock(
			MetadataRebuildPreviewItemRepository.class);
	private final Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

	private final MetadataRunReader reader = new MetadataRunReader(executionRepository, Progress.estimator(clock),
			previewRepository, itemRepository, new ExecutionMessageCodec(new ObjectMapper()), new DateSourceLabels());

	@Test
	void withNoRunEverAskedForThereIsNothingToReport() {
		when(executionRepository.findFirstByExecutionTypeOrderByCreatedAtDesc(ExecutionType.METADATA_REBUILD))
				.thenReturn(Optional.empty());

		Assertions.assertThat(reader.isRunning()).isFalse();
		Assertions.assertThat(reader.processed()).isZero();
		Assertions.assertThat(reader.total()).isZero();
		Assertions.assertThat(reader.eta().state()).isEqualTo(EtaState.NOT_APPLICABLE);
		Assertions.assertThat(reader.lastResult()).isNull();
		Assertions.assertThat(reader.lastError()).isNull();
	}

	@Test
	void aRunningPassReportsItsProgressAndItsEstimateFromTheRow() {
		Execution running = execution(ExecutionStatus.RUNNING, true);

		running.setStartedAt(NOW.minusMinutes(1));
		running.setFilesAnalyzed(50);
		running.setTotalExpected(100);

		// The estimate is the window's, not the run's: half of them arrived in the
		// minute measured, so the other half take another minute.
		running.setRateWindowFromAt(NOW.minusMinutes(1));
		running.setRateWindowFromDone(0);

		latest(running);

		Assertions.assertThat(reader.isRunning()).isTrue();
		Assertions.assertThat(reader.processed()).isEqualTo(50);
		Assertions.assertThat(reader.total()).isEqualTo(100);
		Assertions.assertThat(reader.percent()).isEqualTo(50d);
		Assertions.assertThat(reader.eta().remainingSeconds()).isEqualTo(60);
		Assertions.assertThat(reader.lastResult()).isNull();
	}

	@Test
	void aFinishedPassReportsWhatItDid() {
		Execution finished = execution(ExecutionStatus.FINISHED, true);

		finished.setFinishedAt(NOW);
		finished.setFilesFound(120);
		finished.setFilesMoved(100);
		finished.setCacheHits(12);
		finished.setErrors(8);

		latest(finished);

		Assertions.assertThat(reader.isRunning()).isFalse();
		Assertions.assertThat(reader.lastResult()).isNotNull().satisfies(result -> {
			Assertions.assertThat(result.dryRun()).isFalse();
			Assertions.assertThat(result.candidates()).isEqualTo(120);
			Assertions.assertThat(result.rebuilt()).isEqualTo(100);
			Assertions.assertThat(result.skippedMissing()).isEqualTo(12);
			Assertions.assertThat(result.errors()).isEqualTo(8);
			Assertions.assertThat(result.simulation()).isNull();
		});
	}

	/**
	 * The stored source is an enum and comes back worded here, so a preview
	 * computed by a worker with no language is read in the language of whoever is
	 * looking at it.
	 */
	@Test
	void aFinishedSimulationReportsItsPreviewWithTheSourcesWorded() {
		Execution finished = execution(ExecutionStatus.FINISHED, false);

		finished.setFinishedAt(NOW);

		latest(finished);

		when(previewRepository.findByExecutionId(1L))
				.thenReturn(Optional.of(MetadataRebuildPreviewRecord.builder().executionId(1L).sourcePath(FOLDER)
						.candidates(80).skippedByCutoff(20).examined(50).wouldChange(7).build()));
		when(itemRepository.findByExecutionIdOrderByOrdinalAsc(1L))
				.thenReturn(List.of(MetadataRebuildPreviewItemRecord.builder().executionId(1L).ordinal(0)
						.path(FOLDER + "\\a.jpg").currentDate(NOW.minusDays(2)).currentSource(DateSource.FILE_NAME)
						.newDate(NOW).newSource(DateSource.EXIF).build()));

		Assertions.assertThat(reader.lastResult()).isNotNull().satisfies(result -> {
			Assertions.assertThat(result.dryRun()).isTrue();
			Assertions.assertThat(result.candidates()).isEqualTo(80);
			Assertions.assertThat(result.simulation().skippedByCutoff()).isEqualTo(20);
			Assertions.assertThat(result.simulation().examined()).isEqualTo(50);
			Assertions.assertThat(result.simulation().wouldChange()).isEqualTo(7);
			Assertions.assertThat(result.simulation().preview()).singleElement().satisfies(row -> {
				Assertions.assertThat(row.path()).isEqualTo(FOLDER + "\\a.jpg");
				Assertions.assertThat(row.currentSourceLabel()).isNotBlank();
				Assertions.assertThat(row.newSourceLabel()).isNotBlank();
			});
		});
	}

	/**
	 * A simulation whose rows are gone - the execution outlived its preview - still
	 * says it was a simulation rather than reporting a rebuild of zero files.
	 */
	@Test
	void aSimulationWithoutItsPreviewStillSaysItWasASimulation() {
		Execution finished = execution(ExecutionStatus.FINISHED, false);

		finished.setFinishedAt(NOW);
		finished.setFilesFound(80);

		latest(finished);

		when(previewRepository.findByExecutionId(1L)).thenReturn(Optional.empty());

		Assertions.assertThat(reader.lastResult()).isNotNull().satisfies(result -> {
			Assertions.assertThat(result.dryRun()).isTrue();
			Assertions.assertThat(result.candidates()).isEqualTo(80);
			Assertions.assertThat(result.simulation()).isNull();
		});
	}

	/**
	 * A failed run reports through the error, and never as a result. The reason
	 * comes back worded here, out of the code and the arguments the worker stored -
	 * which is why it can be read in a language the worker never knew.
	 */
	@Test
	void aFailedPassIsReportedAsAWordedErrorAndNotAsAResult() {
		Execution failed = execution(ExecutionStatus.ERROR, true);

		failed.setFinishedAt(NOW);
		failed.setStatusMessage(StatusMessage.coded("backend.metadata.rebuildDeferred", null));

		latest(failed);

		Assertions.assertThat(reader.lastResult()).isNull();
		Assertions.assertThat(reader.lastError()).isNotBlank().doesNotContain("backend.");
	}

	/** A message stored as free text is shown as it was stored. */
	@Test
	void aFailureWithoutACodeIsShownAsItWasStored() {
		Execution failed = execution(ExecutionStatus.ERROR, true);

		failed.setFinishedAt(NOW);
		failed.setStatusMessage(StatusMessage.raw("exiftool is not on this machine"));

		latest(failed);

		Assertions.assertThat(reader.lastError()).isEqualTo("exiftool is not on this machine");
	}

	@Test
	void aFailureThatSaidNothingReportsNothing() {
		Execution failed = execution(ExecutionStatus.ERROR, true);

		failed.setFinishedAt(NOW);

		latest(failed);

		Assertions.assertThat(reader.lastError()).isNull();
	}

	/**
	 * Counters absent from a row - a run of a version that did not write them - are
	 * read as zero rather than as a null the screen would have to guard against.
	 */
	@Test
	void aRowWithoutCountersReportsZeros() {
		Execution finished = execution(ExecutionStatus.FINISHED, true);

		finished.setFinishedAt(NOW);

		latest(finished);

		Assertions.assertThat(reader.lastResult()).isNotNull().satisfies(result -> {
			Assertions.assertThat(result.candidates()).isZero();
			Assertions.assertThat(result.rebuilt()).isZero();
			Assertions.assertThat(result.errors()).isZero();
		});
	}

	private void latest(Execution execution) {
		when(executionRepository.findFirstByExecutionTypeOrderByCreatedAtDesc(ExecutionType.METADATA_REBUILD))
				.thenReturn(Optional.of(execution));
	}

	private Execution execution(ExecutionStatus status, boolean writes) {
		return Execution.builder().id(1L).executionType(ExecutionType.METADATA_REBUILD).status(status)
				.sourcePath(FOLDER).executeFlag(writes).build();
	}

	@Test
	void anEstimateNeedsARunThatStarted() {
		latest(execution(ExecutionStatus.PENDING, true));

		Assertions.assertThat(reader.eta().state()).isEqualTo(EtaState.NOT_APPLICABLE);
		Assertions.assertThat(reader.percent()).isEqualTo(-1);
	}
}