package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.AdminBoundaryKind;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;

/**
 * The pipeline's reports, now that they land on a row instead of in a field.
 *
 * <p>
 * What has to be true is that nothing here is the answer to anything: it writes,
 * and a run nobody attached writes nowhere - which is the state an import called
 * outside a queued run is in.
 */
class GeoDatasetProgressTest {

	private final ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);

	private final GeoDatasetProgress progress = new GeoDatasetProgress(executionProgressService);

	private final ExecutionOwnership ownership = Takings.owning(1L);

	/** Three levels: the unit of an update is neither files nor bytes. */
	@Test
	void attachingSaysHowManyStepsTheRunHas() {
		progress.attach(ownership);

		verify(executionProgressService).updateTotal(ownership, AdminBoundaryKind.values().length);
	}

	@Test
	void aDownloadSaysWhichLevelAndStartsTheStepAtZero() {
		progress.attach(ownership);
		progress.startDownload(AdminBoundaryKind.STATE, 1_000);

		ArgumentCaptor<ExecutionMessage> message = ArgumentCaptor.forClass(ExecutionMessage.class);

		verify(executionProgressService).updatePhase(eq(ownership), eq(ExecutionPhase.SCANNING),
				eq(ExecutionStepType.PROGRESS_UPDATED), message.capture());
		verify(executionProgressService).startsCurrentItem(ownership);

		Assertions.assertThat(message.getValue().code()).isEqualTo("backend.geodata.downloading");
		Assertions.assertThat(message.getValue().args()).containsExactly("settings.geo.step.state");
	}

	@Test
	void anImportSaysWhichLevelAndIsADifferentPhase() {
		progress.attach(ownership);
		progress.startImport(AdminBoundaryKind.MUNICIPALITY, 2_000);

		ArgumentCaptor<ExecutionMessage> message = ArgumentCaptor.forClass(ExecutionMessage.class);

		verify(executionProgressService).updatePhase(eq(ownership), eq(ExecutionPhase.PROCESSING), any(),
				message.capture());

		Assertions.assertThat(message.getValue().code()).isEqualTo("backend.geodata.importing");
		Assertions.assertThat(message.getValue().args()).containsExactly("settings.geo.step.municipality");
	}

	/**
	 * Bytes become a percentage of the current step, which is what the row keeps.
	 * How often that reaches the database is the column's own throttle, which is
	 * why this can be called per buffer.
	 */
	@Test
	void bytesBecomeAPercentageOfTheStep() {
		progress.attach(ownership);
		progress.startDownload(AdminBoundaryKind.COUNTRY, 200);
		progress.addDownloadedBytes(50);
		progress.addDownloadedBytes(50);

		verify(executionProgressService).updateCurrentItem(ownership, 25);
		verify(executionProgressService).updateCurrentItem(ownership, 50);
	}

	/** A step whose size the server did not say has no percentage to report. */
	@Test
	void aStepOfUnknownSizeReportsNoPercentage() {
		progress.attach(ownership);
		progress.startImport(AdminBoundaryKind.COUNTRY, -1);
		progress.addImportedBytes(500);

		verify(executionProgressService, never()).updateCurrentItem(any(), anyInt());
	}

	@Test
	void eachFinishedLevelCountsAsAnItemOfTheRun() {
		progress.attach(ownership);
		progress.levelFinished();
		progress.levelFinished();

		verify(executionProgressService).updateLiveProgress(eq(ownership), eq(3), eq(1), eq(0), eq(0), any());
		verify(executionProgressService).updateLiveProgress(eq(ownership), eq(3), eq(2), eq(0), eq(0), any());
	}

	@Test
	void recordsAccumulateForWhoeverAsksAtTheEnd() {
		progress.attach(ownership);
		progress.addImportedRecords(1_000);
		progress.addImportedRecords(234);

		Assertions.assertThat(progress.recordsImported()).isEqualTo(1_234);
	}

	/**
	 * An import outside a queued run - a test, or a call that never went through
	 * the handler - reports nowhere rather than failing. There is no row to write
	 * to, and inventing one would be inventing a run.
	 */
	@Test
	void withNothingAttachedItReportsNowhere() {
		progress.startDownload(AdminBoundaryKind.COUNTRY, 100);
		progress.addDownloadedBytes(50);
		progress.startImport(AdminBoundaryKind.STATE, 100);
		progress.addImportedBytes(50);
		progress.levelFinished();

		verifyNoInteractions(executionProgressService);
	}

	@Test
	void detachingStopsTheReportsWithoutLosingWhatWasCounted() {
		progress.attach(ownership);
		progress.addImportedRecords(7);
		progress.detach();

		progress.levelFinished();

		Assertions.assertThat(progress.recordsImported()).isEqualTo(7);

		verify(executionProgressService, never()).updateLiveProgress(any(), anyInt(),
				anyInt(), anyInt(),
				anyInt(), any());
	}

	/** A second run starts from zero, whatever the first one left. */
	@Test
	void attachingClearsWhatThePreviousRunLeft() {
		progress.attach(ownership);
		progress.addImportedRecords(500);

		progress.attach(ownership);

		Assertions.assertThat(progress.recordsImported()).isZero();
	}
}