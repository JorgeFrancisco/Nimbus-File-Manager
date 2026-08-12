package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

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
 * the process being asked. Read from the rows, the same panel reports a run this
 * application never started and keeps reporting it after a restart.
 */
class GeoRunReaderTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.MAY, 1, 10, 0);

	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
	private final ExecutionMessageCodec executionMessageCodec = new ExecutionMessageCodec(new ObjectMapper());
	private final Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

	private final GeoRunReader reader = new GeoRunReader(executionRepository, executionMessageCodec, clock);

	@Test
	void withNothingEverAskedForThereIsNothingToReport() {
		Assertions.assertThat(reader.busy()).isFalse();
		Assertions.assertThat(reader.rebuildRunning()).isFalse();
		Assertions.assertThat(reader.importRunning()).isFalse();
		Assertions.assertThat(reader.rebuildProcessed()).isZero();
		Assertions.assertThat(reader.rebuildEtaSeconds()).isEqualTo(-1);
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

		latest(ExecutionType.LOCATION_REBUILD, execution);

		Assertions.assertThat(reader.rebuildProcessed()).isEqualTo(50);
		Assertions.assertThat(reader.rebuildTotal()).isEqualTo(100);
		Assertions.assertThat(reader.rebuildPercent()).isEqualTo(50d);
		Assertions.assertThat(reader.rebuildEtaSeconds()).isEqualTo(60);
		Assertions.assertThat(reader.lastRebuildResult()).isNull();
	}

	/**
	 * The scope comes back with the numbers, out of the key the request was
	 * deduplicated by - so the panel says which rebuild the counters belong to.
	 */
	@Test
	void aFinishedRebuildReportsWhatItDidAndForWhichScope() {
		Execution execution = Execution.builder().id(1L).executionType(ExecutionType.LOCATION_REBUILD)
				.status(ExecutionStatus.FINISHED).dedupKey(LocationRebuildScope.LOW_CONFIDENCE.name())
				.finishedAt(NOW).filesFound(100).filesMoved(80).cacheHits(15).errors(5).build();

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
		when(executionRepository.findFirstByExecutionTypeOrderByCreatedAtDesc(type))
				.thenReturn(Optional.of(execution));
	}
}