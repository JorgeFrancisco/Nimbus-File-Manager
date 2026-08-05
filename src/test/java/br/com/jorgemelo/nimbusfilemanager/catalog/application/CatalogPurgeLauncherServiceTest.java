package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.constants.CatalogConstants;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.CatalogPurgePayload;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

/**
 * Asking for the catalog retention purge, and the cheap question it asks first.
 *
 * <p>
 * The check exists so a quiet day leaves no row on the executions screen - it
 * decides whether to ask, never what to remove, which is settled by the worker
 * against the clock of the moment it runs.
 */
class CatalogPurgeLauncherServiceTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-06T09:00:00Z"), ZoneOffset.UTC);

	private final CatalogFileRepository catalogFileRepository = mock(CatalogFileRepository.class);
	private final ExecutionEnqueueService executionEnqueueService = mock(ExecutionEnqueueService.class);
	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(new ObjectMapper());
	private final CatalogPurgeLauncherService launcher = new CatalogPurgeLauncherService(catalogFileRepository,
			executionEnqueueService, executionPayloadCodec, new ExecutionMessageCodec(new ObjectMapper()), CLOCK);

	@Test
	void asksNothingWhenRetentionIsDisabled() {
		Assertions.assertThat(launcher.launch(0)).isEmpty();
		Assertions.assertThat(launcher.launch(-1)).isEmpty();

		verify(catalogFileRepository, never()).existsByLifecycleStatusAndLifecycleChangedAtBefore(any(), any());
		verify(executionEnqueueService, never()).enqueueOrExisting(any());
	}

	/** A quiet day leaves no row behind. */
	@Test
	void queuesNothingWhenNothingIsPastTheWindow() {
		when(catalogFileRepository.existsByLifecycleStatusAndLifecycleChangedAtBefore(eq(LifecycleStatus.MISSING),
				any())).thenReturn(false);

		Assertions.assertThat(launcher.launch(90)).isEmpty();

		verify(executionEnqueueService, never()).enqueueOrExisting(any());
	}

	/**
	 * Only MISSING rows are asked about: a DELETED file is the quarantine's to
	 * expunge, and counting it here would queue a purge that removes nothing.
	 */
	@Test
	void asksOnlyAboutRecordsMissingForLongerThanTheWindow() {
		when(catalogFileRepository.existsByLifecycleStatusAndLifecycleChangedAtBefore(any(), any())).thenReturn(false);

		launcher.launch(90);

		ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.captor();

		verify(catalogFileRepository).existsByLifecycleStatusAndLifecycleChangedAtBefore(eq(LifecycleStatus.MISSING),
				cutoff.capture());

		Assertions.assertThat(cutoff.getValue()).isEqualTo(LocalDateTime.now(CLOCK).minusDays(90));
	}

	/**
	 * The window travels, keyed on itself: the timer fires daily and a request
	 * already waiting for the same window is that same request, not a second one.
	 */
	@Test
	void queuesThePurgeWithTheWindowAndKeepsItDeduplicated() {
		when(catalogFileRepository.existsByLifecycleStatusAndLifecycleChangedAtBefore(any(), any())).thenReturn(true);
		when(executionEnqueueService.enqueueOrExisting(any())).thenAnswer(invocation -> {
			Execution request = invocation.getArgument(0);

			request.setId(1L);
			request.setStatus(ExecutionStatus.PENDING);

			return request;
		});

		Optional<Execution> queued = launcher.launch(90);

		Assertions.assertThat(queued).isPresent();

		Execution execution = queued.orElseThrow();

		Assertions.assertThat(execution.getExecutionType()).isEqualTo(ExecutionType.CATALOG_PURGE);
		Assertions.assertThat(execution.getTriggerEvent()).isEqualTo(ExecutionTrigger.TIMER);
		Assertions.assertThat(execution.getDedupKey()).isEqualTo("90");

		CatalogPurgePayload payload = executionPayloadCodec.decode(execution.getRequestPayload(),
				CatalogPurgePayload.class);

		Assertions.assertThat(payload.schemaVersion()).isEqualTo(CatalogConstants.PAYLOAD_SCHEMA_VERSION);
		Assertions.assertThat(payload.retentionDays()).isEqualTo(90);
	}
}