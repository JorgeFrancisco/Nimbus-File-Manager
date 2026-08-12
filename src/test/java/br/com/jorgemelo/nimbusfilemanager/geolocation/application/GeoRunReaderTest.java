package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.EtaState;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Progress;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationRebuildScope;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.Phase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * Where the geographic panel's answers come from now.
 *
 * <p>
 * Two runners used to hold them, which worked only while the work happened in
 * the process being asked. Read from the rows, the same panel reports a run
 * this application never started and keeps reporting it after a restart.
 */
class GeoRunReaderTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.MAY, 1, 10, 0);

	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
	private final ExecutionMessageCodec executionMessageCodec = new ExecutionMessageCodec(new ObjectMapper());
	private final Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

	private final GeoRunReader reader = new GeoRunReader(executionRepository, Progress.estimator(clock),
			executionMessageCodec, clock);

	@Test
	void withNothingEverAskedForThereIsNothingToReport() {
		Assertions.assertThat(reader.busy()).isFalse();
		Assertions.assertThat(reader.rebuildRunning()).isFalse();
		Assertions.assertThat(reader.importRunning()).isFalse();
		Assertions.assertThat(reader.rebuildProcessed()).isZero();
		Assertions.assertThat(reader.rebuildEta().state()).isEqualTo(EtaState.NOT_APPLICABLE);
		Assertions.assertThat(reader.lastRebuildResult()).isNull();
		Assertions.assertThat(reader.rebuildError()).isNull();
		Assertions.assertThat(reader.progress().phase()).isEqualTo(Phase.IDLE);
	}

	/**
	 * The panel's destructive actions asked two runners whether it was safe. One
	 * question now, and it answers for either kind and for either process.
	 */
	@Test
	void eitherKindOfRunMakesTheSectionBusy() {
		latest(ExecutionType.LOCATION_REBUILD, running(ExecutionType.LOCATION_REBUILD));

		Assertions.assertThat(reader.busy()).isTrue();
		Assertions.assertThat(reader.rebuildRunning()).isTrue();
		Assertions.assertThat(reader.importRunning()).isFalse();
	}

	@Test
	void aRunningRebuildReportsItsProgressAndItsEstimateFromTheRow() {
		Execution execution = running(ExecutionType.LOCATION_REBUILD);

		execution.setStartedAt(NOW.minusMinutes(1));
		execution.setFilesAnalyzed(50);
		execution.setTotalExpected(100);

		// The estimate is the window's, not the run's: half of them arrived in the
		// minute measured, so the other half take another minute.
		execution.setRateWindowFromAt(NOW.minusMinutes(1));
		execution.setRateWindowFromDone(0);

		latest(ExecutionType.LOCATION_REBUILD, execution);

		Assertions.assertThat(reader.rebuildProcessed()).isEqualTo(50);
		Assertions.assertThat(reader.rebuildTotal()).isEqualTo(100);
		Assertions.assertThat(reader.rebuildPercent()).isEqualTo(50d);
		Assertions.assertThat(reader.rebuildEta().remainingSeconds()).isEqualTo(60);
		Assertions.assertThat(reader.lastRebuildResult()).isNull();
	}

	/**
	 * The scope comes back with the numbers, out of the key the request was
	 * deduplicated by - so the panel says which rebuild the counters belong to.
	 */
	@Test
	void aFinishedRebuildReportsWhatItDidAndForWhichScope() {
		Execution execution = Execution.builder().id(1L).executionType(ExecutionType.LOCATION_REBUILD)
				.status(ExecutionStatus.FINISHED).dedupKey(LocationRebuildScope.LOW_CONFIDENCE.name()).finishedAt(NOW)
				.filesFound(100).filesMoved(80).cacheHits(15).errors(5).build();

		latest(ExecutionType.LOCATION_REBUILD, execution);

		Assertions.assertThat(reader.lastRebuildResult()).isNotNull().satisfies(result -> {
			Assertions.assertThat(result.scope()).isEqualTo(LocationRebuildScope.LOW_CONFIDENCE);
			Assertions.assertThat(result.candidates()).isEqualTo(100);
			Assertions.assertThat(result.resolved()).isEqualTo(80);
			Assertions.assertThat(result.unresolved()).isEqualTo(15);
			Assertions.assertThat(result.errors()).isEqualTo(5);
		});
	}

	/** A row whose key is not a scope any more still reports its numbers. */
	@Test
	void aRebuildWhoseScopeCannotBeReadFallsBackInsteadOfFailing() {
		Execution execution = Execution.builder().id(1L).executionType(ExecutionType.LOCATION_REBUILD)
				.status(ExecutionStatus.FINISHED).dedupKey("something-else").finishedAt(NOW).filesFound(3).build();

		latest(ExecutionType.LOCATION_REBUILD, execution);

		Assertions.assertThat(reader.lastRebuildResult().scope()).isEqualTo(LocationRebuildScope.PENDING);
	}

	/**
	 * Which level and how far into it, out of the phase and the message - the two
	 * things the run wrote to the row as it went.
	 */
	@Test
	void anUpdateReportsWhichLevelItIsOnAndHowFarIntoIt() {
		Execution execution = running(ExecutionType.GEO_DATASET_UPDATE);

		execution.setPhase(ExecutionPhase.PROCESSING);
		execution.setCurrentItemPercent(42);
		// The level is the last segment of the code. It used to be the message's
		// first argument, and when it moved this reader went on decoding an argument
		// that was no longer there - handing the panel an empty key, which the page
		// rendered as the missing-key marker.
		execution.setStatusMessage(StatusMessage.coded("backend.geodata.importing.municipality", null));

		latest(ExecutionType.GEO_DATASET_UPDATE, execution);

		Assertions.assertThat(reader.progress()).satisfies(snapshot -> {
			Assertions.assertThat(snapshot.importing()).isTrue();
			Assertions.assertThat(snapshot.downloading()).isFalse();
			Assertions.assertThat(snapshot.stepLabel()).isEqualTo("settings.geo.step.municipality");
			Assertions.assertThat(snapshot.percent()).isEqualTo(42);
		});
	}

	@Test
	void anUpdateStillDownloadingSaysSo() {
		Execution execution = running(ExecutionType.GEO_DATASET_UPDATE);

		execution.setPhase(ExecutionPhase.SCANNING);

		latest(ExecutionType.GEO_DATASET_UPDATE, execution);

		Assertions.assertThat(reader.progress().downloading()).isTrue();
		Assertions.assertThat(reader.progress().percent()).isEqualTo(-1);

		// A run that has not said anything yet still gets a noun, or the panel says
		// "Downloading" and stops.
		Assertions.assertThat(reader.progress().stepLabel()).isEqualTo("settings.geo.step.dataset");
	}

	/**
	 * The stages that are not about one administrative level - the territories, the
	 * publication, the finish - name the dataset itself rather than nothing.
	 */
	@Test
	void aStageThatIsNotAboutOneLevelStillNamesSomething() {
		Execution execution = running(ExecutionType.GEO_DATASET_UPDATE);

		execution.setPhase(ExecutionPhase.PROCESSING);
		execution.setStatusMessage(StatusMessage.coded("backend.geodata.publishing", null));

		latest(ExecutionType.GEO_DATASET_UPDATE, execution);

		Assertions.assertThat(reader.progress().stepLabel()).isEqualTo("settings.geo.step.dataset");
	}

	/** A finished update is not progress: the panel shows the dataset instead. */
	@Test
	void aFinishedUpdateReportsNoProgressAtAll() {
		Execution execution = Execution.builder().id(1L).executionType(ExecutionType.GEO_DATASET_UPDATE)
				.status(ExecutionStatus.FINISHED).finishedAt(NOW).build();

		latest(ExecutionType.GEO_DATASET_UPDATE, execution);

		Assertions.assertThat(reader.progress().phase()).isEqualTo(Phase.IDLE);
		Assertions.assertThat(reader.importRunning()).isFalse();
	}

	@Test
	void aFailedRunReportsItsReasonWorded() {
		Execution execution = Execution.builder().id(1L).executionType(ExecutionType.GEO_DATASET_UPDATE)
				.status(ExecutionStatus.ERROR).finishedAt(NOW)
				.statusMessage(StatusMessage.raw("the server refused the connection")).build();

		latest(ExecutionType.GEO_DATASET_UPDATE, execution);

		Assertions.assertThat(reader.importError()).isEqualTo("the server refused the connection");
		Assertions.assertThat(reader.rebuildError()).isNull();
	}

	/**
	 * A reason the worker wrote as a code plus its arguments comes back as a
	 * sentence in the language of whoever is reading - which is the point of the
	 * worker never storing text.
	 */
	@Test
	void aCodedReasonComesBackWorded() {
		Execution execution = Execution.builder().id(1L).executionType(ExecutionType.LOCATION_REBUILD)
				.status(ExecutionStatus.ERROR).finishedAt(NOW)
				.statusMessage(StatusMessage.coded("backend.geodata.deferred", null)).build();

		latest(ExecutionType.LOCATION_REBUILD, execution);

		Assertions.assertThat(reader.rebuildError()).isNotBlank().doesNotContain("backend.");
	}

	@Test
	void aFailureThatSaidNothingReportsNothing() {
		Execution execution = Execution.builder().id(1L).executionType(ExecutionType.LOCATION_REBUILD)
				.status(ExecutionStatus.ERROR).finishedAt(NOW).build();

		latest(ExecutionType.LOCATION_REBUILD, execution);

		Assertions.assertThat(reader.rebuildError()).isNull();
	}

	/** An update running is enough to make the section busy on its own. */
	@Test
	void anUpdateAloneAlsoMakesTheSectionBusy() {
		latest(ExecutionType.GEO_DATASET_UPDATE, running(ExecutionType.GEO_DATASET_UPDATE));

		Assertions.assertThat(reader.busy()).isTrue();
		Assertions.assertThat(reader.importRunning()).isTrue();
		Assertions.assertThat(reader.rebuildRunning()).isFalse();
	}

	private Execution running(ExecutionType type) {
		return Execution.builder().id(1L).executionType(type).status(ExecutionStatus.RUNNING)
				.dedupKey(LocationRebuildScope.PENDING.name()).build();
	}

	private void latest(ExecutionType type, Execution execution) {
		when(executionRepository.findFirstByExecutionTypeOrderByCreatedAtDesc(type)).thenReturn(Optional.of(execution));
	}

	/**
	 * The daily obligation, answered from the rows rather than from a field a
	 * restart clears.
	 *
	 * <p>
	 * The timer used to remember in memory that it had checked today, so the first
	 * tick after every restart asked for another update - and with the old
	 * behaviour that meant a second full reimport of every boundary minutes after
	 * the first. A finished run already records that it finished, and when.
	 */
	@Test
	void aRunThatFinishedTodayDischargesTodaysObligation() {
		finishedAt(NOW.minusHours(3));

		Assertions.assertThat(reader.completedToday()).isTrue();
		Assertions.assertThat(reader.lastVerifiedAt()).isEqualTo(NOW.minusHours(3));
	}

	/** Yesterday's run discharges yesterday. */
	@Test
	void aRunThatFinishedYesterdayOwesToday() {
		finishedAt(NOW.minusDays(1));

		Assertions.assertThat(reader.completedToday()).isFalse();
	}

	/**
	 * A run that crossed midnight counts for the day it finished: it is the most
	 * recent look anyone has taken at the source, and calling that "not today"
	 * would ask for another one minutes later.
	 */
	@Test
	void aRunThatStartedYesterdayAndFinishedTodayCountsForToday() {
		finishedAt(NOW.toLocalDate().atStartOfDay().plusMinutes(10));

		Assertions.assertThat(reader.completedToday()).isTrue();
	}

	/**
	 * Nothing has ever finished, so nothing has been verified - the state of a
	 * fresh installation, which must ask.
	 */
	@Test
	void withNoFinishedRunNothingHasBeenVerified() {
		Assertions.assertThat(reader.completedToday()).isFalse();
		Assertions.assertThat(reader.lastVerifiedAt()).isNull();
	}

	/**
	 * Only a finished run counts, and the query is what enforces it: a rejected,
	 * cancelled or failed run never reached the source and owes the day the same
	 * check it always did.
	 */
	@Test
	void asksTheHistoryOnlyForRunsThatFinished() {
		reader.completedToday();

		verify(executionRepository).findFirstByExecutionTypeAndStatusOrderByFinishedAtDesc(
				ExecutionType.GEO_DATASET_UPDATE, ExecutionStatus.FINISHED);
	}

	private void finishedAt(LocalDateTime finishedAt) {
		Execution execution = new Execution();

		execution.setExecutionType(ExecutionType.GEO_DATASET_UPDATE);
		execution.setStatus(ExecutionStatus.FINISHED);
		execution.setFinishedAt(finishedAt);

		when(executionRepository.findFirstByExecutionTypeAndStatusOrderByFinishedAtDesc(
				ExecutionType.GEO_DATASET_UPDATE, ExecutionStatus.FINISHED)).thenReturn(Optional.of(execution));
	}

	/**
	 * Every level the pipeline names, so the panel's sentence is complete rather
	 * than complete for the one level somebody happened to test. The label comes
	 * from the last segment of the code, and a level whose case is missing falls
	 * through to the dataset - which reads as a stage that is not happening.
	 */
	@ParameterizedTest
	@CsvSource({ "backend.geodata.downloading.country, settings.geo.step.country",
			"backend.geodata.importing.state, settings.geo.step.state",
			"backend.geodata.importing.municipality, settings.geo.step.municipality",
			"backend.geodata.publishing, settings.geo.step.dataset" })
	void eachLevelOfTheUpdateIsNamedByTheCodeItWrote(String code, String expectedLabel) {
		Execution execution = running(ExecutionType.GEO_DATASET_UPDATE);

		execution.setStatusMessage(StatusMessage.coded(code, null));

		latest(ExecutionType.GEO_DATASET_UPDATE, execution);

		Assertions.assertThat(reader.progress().stepLabel()).isEqualTo(expectedLabel);
	}

	/**
	 * A failed run is what the panel reports as an error, and the only thing it
	 * reports that way: a finished one has a result to show instead, and reading
	 * its message as a failure would put an error on screen over a run that worked.
	 */
	@Test
	void onlyAFailedRunIsReportedAsAnError() {
		Execution execution = new Execution();

		execution.setExecutionType(ExecutionType.GEO_DATASET_UPDATE);
		execution.setStatus(ExecutionStatus.FINISHED);
		execution.setFinishedAt(NOW);
		execution.setStatusMessage(StatusMessage.coded("backend.geodata.updateCompleted", "[52697]"));

		latest(ExecutionType.GEO_DATASET_UPDATE, execution);

		Assertions.assertThat(reader.importError()).as("a finished run is not an error").isNull();

		execution.setStatus(ExecutionStatus.ERROR);

		Assertions.assertThat(reader.importError()).isNotBlank();
	}

	/**
	 * A run that has finished has no time remaining, and one that never started has
	 * nothing to estimate from. Both answer -1, which is what the panel draws as no
	 * estimate at all rather than as "zero seconds left".
	 */
	@Test
	void thereIsNoEstimateForARunThatFinishedOrNeverStarted() {
		Execution finished = running(ExecutionType.LOCATION_REBUILD);

		finished.setStartedAt(NOW.minusMinutes(1));
		finished.setFinishedAt(NOW);

		latest(ExecutionType.LOCATION_REBUILD, finished);

		Assertions.assertThat(reader.rebuildEta().state()).isEqualTo(EtaState.NOT_APPLICABLE);

		Execution neverStarted = running(ExecutionType.LOCATION_REBUILD);

		neverStarted.setStartedAt(null);

		latest(ExecutionType.LOCATION_REBUILD, neverStarted);

		Assertions.assertThat(reader.rebuildEta().state()).isEqualTo(EtaState.NOT_APPLICABLE);
	}

	/**
	 * The last rebuild the panel reports on is one that finished. A run still going
	 * has no result yet, and a failed one is reported as an error instead.
	 */
	@Test
	void theLastRebuildResultIsTheLastOneThatActuallyFinished() {
		Execution failed = new Execution();

		failed.setExecutionType(ExecutionType.LOCATION_REBUILD);
		failed.setStatus(ExecutionStatus.ERROR);
		failed.setFinishedAt(NOW);

		latest(ExecutionType.LOCATION_REBUILD, failed);

		Assertions.assertThat(reader.lastRebuildResult()).as("a failure is not a result").isNull();

		Execution unfinished = running(ExecutionType.LOCATION_REBUILD);

		latest(ExecutionType.LOCATION_REBUILD, unfinished);

		Assertions.assertThat(reader.lastRebuildResult()).as("nor is a run still going").isNull();
	}
}