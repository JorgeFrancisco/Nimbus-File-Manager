package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.OptionalInt;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.constants.CatalogConstants;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.constants.CatalogMessages;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.CatalogPurgePayload;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Forgetting catalog rows off the queue. What the row it closes is for: this
 * removes years of extracted metadata, perceptual hashes and resolved locations
 * that nobody can get back, and until now it happened on a timer with nothing
 * on any screen to show for it.
 */
class CatalogPurgeJobHandlerTest {

	private final CatalogFileRetentionService catalogFileRetentionService = mock(CatalogFileRetentionService.class);
	private final ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);
	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(new ObjectMapper());
	private final CatalogPurgeJobHandler handler = new CatalogPurgeJobHandler(catalogFileRetentionService,
			executionProgressService, executionPayloadCodec);

	@Test
	void answersForTheCatalogPurgeType() {
		Assertions.assertThat(handler.type()).isEqualTo(ExecutionType.CATALOG_PURGE);
	}

	/**
	 * It removes rows matching a condition; running it again removes what is left.
	 */
	@Test
	void isResumable() {
		Assertions.assertThat(handler.resumable()).isTrue();
	}

	/**
	 * It holds no tree, and saying so is the whole point: its work is a query -
	 * catalog rows whose file has been missing longer than the window - and making
	 * it name a folder would have it wait for, and block, work it has nothing to do
	 * with. It touches no file, only rows.
	 */
	@Test
	void takesNoPathLockBecauseItsWorkIsAQueryRatherThanAFolder() {
		Assertions.assertThat(handler.requiresPathLock()).isFalse();
	}

	/**
	 * The window travels, not the rows: what is past it is decided against the
	 * clock of the moment the purge runs, not of the moment it was queued.
	 */
	@Test
	void purgesWithTheWindowThePayloadNamesAndReportsWhatWent() {
		Execution execution = Execution.builder().id(9L).build();

		ExecutionOwnership ownership = Takings.owning(9L);

		when(catalogFileRetentionService.purgeMissingOlderThan(eq(90), any())).thenReturn(OptionalInt.of(7));

		handler.handle(execution, claimed(CatalogConstants.PAYLOAD_SCHEMA_VERSION, 90), ownership);

		verify(executionProgressService).finishCommand(ownership, ExecutionStatus.FINISHED,
				new ExecutionCounts(7, 7, 0, 0), CatalogMessages.purgeCompleted(7));
	}

	/**
	 * A pass that removed nothing says so in words. "0" and "nothing was past the
	 * window" are answers to different questions, and only one of them tells the
	 * person the retention is working.
	 */
	@Test
	void saysNothingWasPastTheWindowRatherThanReportingAZero() {
		Execution execution = Execution.builder().id(10L).build();

		ExecutionOwnership ownership = Takings.owning(10L);

		when(catalogFileRetentionService.purgeMissingOlderThan(anyInt(), any())).thenReturn(OptionalInt.of(0));

		handler.handle(execution, claimed(CatalogConstants.PAYLOAD_SCHEMA_VERSION, 30), ownership);

		verify(executionProgressService).finishCommand(ownership, ExecutionStatus.FINISHED,
				new ExecutionCounts(0, 0, 0, 0), CatalogMessages.purgeFoundNothing());
	}

	/** An absent window is not a licence to purge with a guess. */
	@Test
	void treatsAnAbsentWindowAsZeroSoNothingIsPurged() {
		Execution execution = Execution.builder().id(11L).build();

		when(catalogFileRetentionService.purgeMissingOlderThan(eq(0), any())).thenReturn(OptionalInt.of(0));

		handler.handle(execution, claimed(CatalogConstants.PAYLOAD_SCHEMA_VERSION, null), Takings.owning(11L));

		verify(catalogFileRetentionService).purgeMissingOlderThan(eq(0), any());
	}

	/**
	 * The checkpoint before the purge. It authorises nothing - what the delete
	 * answers to is the pin inside its transaction - but a row that has already
	 * moved on is not worth a transaction and a round trip, and there is no outcome
	 * for this run to write about a row it no longer holds.
	 */
	@Test
	void doesNotEvenAskToPurgeWhenTheRowHasAlreadyMovedOn() {
		Execution execution = Execution.builder().id(13L).build();

		handler.handle(execution, claimed(CatalogConstants.PAYLOAD_SCHEMA_VERSION, 90), Takings.replaced(13L));

		verify(catalogFileRetentionService, never()).purgeMissingOlderThan(anyInt(), any());
		verify(executionProgressService, never()).finishCommand(any(), any(), any(), any());
	}

	/**
	 * The row moved on between the checkpoint and the delete, and the pin caught
	 * what the checkpoint could not: nothing was removed, so there is nothing to
	 * report. Writing an outcome here would put this run's sentence on a row that
	 * belongs to another taking - the one that will write its own.
	 */
	@Test
	void writesNoOutcomeWhenTheRowRefusedThePurgeItHadAlreadyBegun() {
		Execution execution = Execution.builder().id(14L).build();

		when(catalogFileRetentionService.purgeMissingOlderThan(eq(90), any())).thenReturn(OptionalInt.empty());

		handler.handle(execution, claimed(CatalogConstants.PAYLOAD_SCHEMA_VERSION, 90), Takings.owning(14L));

		verify(executionProgressService, never()).finishCommand(any(), any(), any(), any());
	}

	/**
	 * Both refusals are the same refusal: a version this build does not write, and
	 * no version at all - which is a row it cannot read rather than the current
	 * shape.
	 */
	@Test
	void refusesAPayloadWrittenInAShapeItDoesNotKnow() {
		Execution execution = Execution.builder().id(12L).build();

		ExecutionOwnership ownership = Takings.owning(12L);

		ClaimedExecution newer = claimed(CatalogConstants.PAYLOAD_SCHEMA_VERSION + 1, 90);

		ClaimedExecution unversioned = claimed(null, 90);

		Assertions.assertThatThrownBy(() -> handler.handle(execution, newer, ownership))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be run by this version");
		Assertions.assertThatThrownBy(() -> handler.handle(execution, unversioned, ownership))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be run by this version");

		verify(catalogFileRetentionService, never()).purgeMissingOlderThan(anyInt(), any());
		verify(executionProgressService, never()).finishCommand(any(), any(), any(), any());
	}

	private ClaimedExecution claimed(Integer schemaVersion, Integer retentionDays) {
		return new ClaimedExecution(1L, ExecutionType.CATALOG_PURGE.name(), null, null,
				executionPayloadCodec.encode(new CatalogPurgePayload(schemaVersion, retentionDays)));
	}
}