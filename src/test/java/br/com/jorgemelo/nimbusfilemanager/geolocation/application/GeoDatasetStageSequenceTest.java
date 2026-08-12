package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.AdminBoundaryKind;

/**
 * The nine stages of a dataset update, walked in order, watched through the only
 * numbers the first bar has: the counter and its total.
 *
 * <p>
 * The bar is {@code filesFound / totalExpected}, computed once in
 * {@code ExecutionMapper} and drawn by a component that knows nothing about
 * geography. So everything this class asserts is about those two numbers - what
 * the screen shows follows from them, and proving them here proves it without a
 * browser.
 *
 * <p>
 * The defect these hold shut: the counter used to receive the <em>total</em>
 * while the count went into the field beside it, so the bar reached a hundred
 * per cent the moment the first of three levels finished importing - with six
 * stages still to run, and the second bar still moving underneath to say so.
 */
class GeoDatasetStageSequenceTest {

	private static final int STAGES = 9;

	private final ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);

	private final GeoDatasetProgress progress = new GeoDatasetProgress(executionProgressService);

	private final ExecutionOwnership ownership = Takings.owning(1L);

	@Test
	void theWholePipelineAdvancesOneStageAtATimeAndReachesTheTotalOnlyAtTheEnd() {
		progress.attach(ownership);

		walkTheWholePipeline();

		Assertions.assertThat(countersWritten()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
	}

	/**
	 * Every stage before the last leaves the bar short of its total, which is the
	 * property the person watching actually relies on: a run that says it is
	 * complete has nothing left to do.
	 */
	@Test
	void noStageBeforeTheLastReachesTheTotal() {
		progress.attach(ownership);

		walkTheWholePipeline();

		List<Integer> counters = countersWritten();

		Assertions.assertThat(counters.subList(0, counters.size() - 1)).allSatisfy(
				counter -> Assertions.assertThat(counter).isLessThan(STAGES));

		Assertions.assertThat(counters.getLast()).isEqualTo(STAGES);
	}

	/**
	 * The third import is the sixth stage, not the last one. This is the exact
	 * reading that used to say a hundred per cent while the territories, the
	 * publication and the cache were all still ahead.
	 */
	@Test
	void theThirdImportLeavesTheRunAtSixOfNine() {
		progress.attach(ownership);

		acquireEveryLevel();
		importEveryLevel();

		Assertions.assertThat(countersWritten().getLast()).isEqualTo(6);
	}

	/** Territories and publication are stages seven and eight, still short of nine. */
	@Test
	void territoriesAndPublicationStayBelowTheTotal() {
		progress.attach(ownership);

		acquireEveryLevel();
		importEveryLevel();

		progress.completingTerritories();
		progress.stageFinished();

		Assertions.assertThat(countersWritten().getLast()).isEqualTo(7);

		progress.publishing();
		progress.stageFinished();

		Assertions.assertThat(countersWritten().getLast()).isEqualTo(8).isLessThan(STAGES);
	}

	/**
	 * A stage that failed is not a stage that happened. The run stops where it
	 * stopped: the row goes on naming the stage it was in - which is how anybody
	 * finds out where it broke - and the counter is the one the previous stage
	 * left.
	 */
	@Test
	void aStageThatFailsKeepsItsIdentityAndIsNotCounted() {
		progress.attach(ownership);

		acquireEveryLevel();

		progress.importing(AdminBoundaryKind.COUNTRY, 2_000);

		// The import throws here: nothing calls stageFinished, which is the whole of
		// the guarantee - the count is only ever moved by a stage that returned.
		Assertions.assertThat(countersWritten().getLast()).isEqualTo(3);

		Assertions.assertThat(lastMessage().code()).isEqualTo("backend.geodata.importing.country");
	}

	/**
	 * A level already on disk and a level nobody configured are stages too. They
	 * name themselves and they count, because a pipeline that shortened itself when
	 * there was less to do would read as a different pipeline - and a person
	 * watching could not tell that from two stages having failed.
	 */
	@Test
	void stagesWithNoWorkStillNameThemselvesAndStillCount() {
		progress.attach(ownership);

		progress.alreadyAvailable(AdminBoundaryKind.COUNTRY);
		progress.stageFinished();

		progress.levelNotConfigured(AdminBoundaryKind.STATE);
		progress.stageFinished();

		Assertions.assertThat(countersWritten()).containsExactly(1, 2);

		ArgumentCaptor<ExecutionMessage> messages = ArgumentCaptor.forClass(ExecutionMessage.class);

		verify(executionProgressService, atLeastOnce()).updatePhase(eq(ownership), any(), any(), messages.capture());

		Assertions.assertThat(messages.getAllValues()).extracting(ExecutionMessage::code).containsExactly(
				"backend.geodata.alreadyAvailable.country", "backend.geodata.levelNotConfigured.state");
	}

	/**
	 * A stage with nothing to measure takes the second bar away rather than parking
	 * it at zero. Zero draws an empty bar that reads as stuck; absent draws no bar,
	 * which is the truth - there is no denominator to be a fraction of.
	 */
	@Test
	void aStageWithNothingToMeasureLeavesNoSecondBar() {
		progress.attach(ownership);

		progress.publishing();

		verify(executionProgressService).clearsCurrentItem(ownership);
		verify(executionProgressService, never()).startsCurrentItem(ownership);
		verify(executionProgressService, never()).updateCurrentItem(any(), anyInt());
	}

	/** A measurable stage does the opposite: the bar comes back and starts at zero. */
	@Test
	void aMeasurableStageStartsTheSecondBarAtZero() {
		progress.attach(ownership);

		progress.downloading(AdminBoundaryKind.STATE, 4_000);

		verify(executionProgressService).startsCurrentItem(ownership);
		verify(executionProgressService, never()).clearsCurrentItem(ownership);
	}

	/**
	 * The second bar restarting is expected and says nothing about the first: a new
	 * stage begins its own measurement while the global count only ever grows.
	 */
	@Test
	void theSecondBarRestartsPerStageWhileTheGlobalCountOnlyGrows() {
		progress.attach(ownership);

		progress.downloading(AdminBoundaryKind.COUNTRY, 100);
		progress.addDownloadedBytes(100);
		progress.stageFinished();

		progress.downloading(AdminBoundaryKind.STATE, 100);
		progress.addDownloadedBytes(25);
		progress.stageFinished();

		verify(executionProgressService).updateCurrentItem(ownership, 100);
		verify(executionProgressService).updateCurrentItem(ownership, 25);

		Assertions.assertThat(countersWritten()).containsExactly(1, 2);
	}

	/**
	 * What the handler reads when it closes the row, so a finished update says nine
	 * of nine rather than a number that means something else.
	 */
	@Test
	void theRunKnowsHowManyStagesItHasBehindIt() {
		progress.attach(ownership);

		Assertions.assertThat(progress.stagesDone()).isZero();

		walkTheWholePipeline();

		Assertions.assertThat(progress.stagesDone()).isEqualTo(STAGES);
	}

	/**
	 * Territories with nothing to complete is still the seventh stage: it says so
	 * and it counts, which is why the pipeline reads the same nine whether the
	 * setting is on, off, or on with nothing missing.
	 */
	@Test
	void territoriesWithNothingToDoStillNameThemselvesAndCount() {
		progress.attach(ownership);

		acquireEveryLevel();
		importEveryLevel();

		progress.noTerritoriesMissing();
		progress.stageFinished();

		Assertions.assertThat(countersWritten().getLast()).isEqualTo(7);

		Assertions.assertThat(lastMessage().code()).isEqualTo("backend.geodata.noTerritoriesMissing");
	}

	private void walkTheWholePipeline() {
		acquireEveryLevel();
		importEveryLevel();

		progress.completingTerritories();
		progress.stageFinished();

		progress.publishing();
		progress.stageFinished();

		progress.finishing();
		progress.stageFinished();
	}

	private void acquireEveryLevel() {
		for (AdminBoundaryKind kind : AdminBoundaryKind.values()) {
			progress.downloading(kind, 1_000);
			progress.stageFinished();
		}
	}

	private void importEveryLevel() {
		for (AdminBoundaryKind kind : AdminBoundaryKind.values()) {
			progress.importing(kind, 1_000);
			progress.stageFinished();
		}
	}

	/** Every value the counter behind the first bar was given, in order. */
	private List<Integer> countersWritten() {
		ArgumentCaptor<Integer> counter = ArgumentCaptor.forClass(Integer.class);

		verify(executionProgressService, atLeastOnce()).updateLiveProgress(eq(ownership), counter.capture(), anyInt(),
				anyInt(), anyInt(), any());

		return new ArrayList<>(counter.getAllValues());
	}

	private ExecutionMessage lastMessage() {
		ArgumentCaptor<ExecutionMessage> messages = ArgumentCaptor.forClass(ExecutionMessage.class);

		verify(executionProgressService, atLeastOnce()).updatePhase(eq(ownership), any(), any(), messages.capture());

		return messages.getAllValues().getLast();
	}
}