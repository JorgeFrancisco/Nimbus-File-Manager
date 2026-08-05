package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionStatusNames;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionActivity;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionActivitySnapshot;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ExecutionLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

/**
 * The banner's single source of truth. What is asserted here is the behaviour
 * the two mechanisms it replaces could not have: the answer describes whatever
 * is active now, so nothing depends on an id chosen when a page was rendered.
 */
class ExecutionActivityServiceTest {

	private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
	private static final LocalDateTime NOON = LocalDateTime.of(2026, Month.AUGUST, 6, 12, 0);

	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
	private final ExecutionLabels executionLabels = labels();
	private final ExecutionMapper executionMapper = mapper();
	private final ExecutionActivityService service = new ExecutionActivityService(executionRepository, executionLabels,
			executionMapper);

	private long nextId = 1L;

	@AfterEach
	void resetLocale() {
		LocaleContextHolder.resetLocaleContext();
	}

	@Test
	void answersThatNothingIsHappeningRatherThanRefusingToAnswer() {
		when(executionRepository.findByStatusIn(any())).thenReturn(List.of());

		ExecutionActivitySnapshot snapshot = service.current();

		assertThat(snapshot.primary()).isNull();
		assertThat(snapshot.others()).isEmpty();
		assertThat(snapshot.totalActive()).isZero();
	}

	/**
	 * Only what can still move is asked for: a finished execution is history, and
	 * the banner would be announcing something that is over.
	 */
	@Test
	void asksOnlyForWorkThatCanStillMove() {
		when(executionRepository.findByStatusIn(any())).thenReturn(List.of());

		service.current();

		verify(executionRepository).findByStatusIn(ExecutionStatusNames.ACTIVE);
	}

	@Test
	void describesTheRunningWorkWithLabelsAlreadyResolved() {
		LocaleContextHolder.setLocale(PT_BR);

		when(executionRepository.findByStatusIn(any()))
				.thenReturn(List.of(execution(ExecutionType.INVENTORY, ExecutionStatus.RUNNING, 0, NOON)));

		ExecutionActivity primary = service.current().primary();

		assertThat(primary.executionType()).isEqualTo("INVENTORY");
		assertThat(primary.typeLabel()).isEqualTo("Inventário");
		assertThat(primary.status()).isEqualTo("RUNNING");
		assertThat(primary.statusLabel()).isEqualTo("Em execução");
	}

	/**
	 * Waiting work is shown, not hidden. Queued is what the user sees for most of
	 * the wait when something else holds the lock, and a banner that only appears
	 * once work starts leaves exactly that stretch unexplained - which is how a geo
	 * dataset update sat behind an inventory with nothing on screen to say so.
	 */
	@Test
	void workThatIsOnlyWaitingIsStillWorthShowing() {
		LocaleContextHolder.setLocale(PT_BR);

		when(executionRepository.findByStatusIn(any()))
				.thenReturn(List.of(execution(ExecutionType.GEO_DATASET_UPDATE, ExecutionStatus.PENDING, 0, NOON)));

		ExecutionActivitySnapshot snapshot = service.current();

		assertThat(snapshot.primary().status()).isEqualTo("PENDING");
		assertThat(snapshot.primary().statusLabel()).isEqualTo("Na fila");
		assertThat(snapshot.totalActive()).isEqualTo(1);
	}

	@Test
	void whatIsRunningOutranksWhatIsOnlyWaitingHoweverUrgent() {
		when(executionRepository.findByStatusIn(any())).thenReturn(
				List.of(execution(ExecutionType.GEO_DATASET_UPDATE, ExecutionStatus.PENDING, 900, NOON),
						execution(ExecutionType.INVENTORY, ExecutionStatus.RUNNING, 0, NOON)));

		ExecutionActivitySnapshot snapshot = service.current();

		assertThat(snapshot.primary().executionType()).isEqualTo("INVENTORY");
		assertThat(snapshot.others()).extracting(ExecutionActivity::executionType)
				.containsExactly("GEO_DATASET_UPDATE");
	}

	@Test
	void amongWaitingWorkTheHigherPriorityComesFirst() {
		when(executionRepository.findByStatusIn(any()))
				.thenReturn(List.of(execution(ExecutionType.CONVERSION, ExecutionStatus.PENDING, 1, NOON),
						execution(ExecutionType.INVENTORY, ExecutionStatus.PENDING, 5, NOON)));

		ExecutionActivitySnapshot snapshot = service.current();

		assertThat(snapshot.primary().executionType()).isEqualTo("INVENTORY");
		assertThat(snapshot.others()).extracting(ExecutionActivity::executionType).containsExactly("CONVERSION");
	}

	@Test
	void amongEqualPrioritiesTheOneThatHasWaitedLongestComesFirst() {
		when(executionRepository.findByStatusIn(any()))
				.thenReturn(List.of(execution(ExecutionType.CONVERSION, ExecutionStatus.PENDING, 0, NOON),
						execution(ExecutionType.INVENTORY, ExecutionStatus.PENDING, 0, NOON.minusHours(2))));

		ExecutionActivitySnapshot snapshot = service.current();

		assertThat(snapshot.primary().executionType()).isEqualTo("INVENTORY");
	}

	@Test
	void everythingActiveIsCountedAndOnlyTheFirstIsDrawnInFull() {
		when(executionRepository.findByStatusIn(any()))
				.thenReturn(List.of(execution(ExecutionType.INVENTORY, ExecutionStatus.RUNNING, 0, NOON),
						execution(ExecutionType.CONVERSION, ExecutionStatus.RUNNING, 0, NOON.plusMinutes(1)),
						execution(ExecutionType.METADATA_REBUILD, ExecutionStatus.PENDING, 0, NOON)));

		ExecutionActivitySnapshot snapshot = service.current();

		assertThat(snapshot.totalActive()).isEqualTo(3);
		assertThat(snapshot.primary().executionType()).isEqualTo("INVENTORY");
		assertThat(snapshot.others()).extracting(ExecutionActivity::executionType).containsExactly("CONVERSION",
				"METADATA_REBUILD");
	}

	/**
	 * A type that never reports a total says nothing rather than reporting zero: a
	 * bar frozen at 0% reads as stuck, and a purge that is working fine would look
	 * broken for as long as it ran.
	 */
	@Test
	void workWithNoDenominatorReportsNoPercentageAtAll() {
		Execution execution = execution(ExecutionType.QUARANTINE_PURGE, ExecutionStatus.RUNNING, 0, NOON);

		execution.setTotalExpected(null);
		execution.setFilesFound(37);

		when(executionRepository.findByStatusIn(any())).thenReturn(List.of(execution));

		ExecutionActivity primary = service.current().primary();

		assertThat(primary.percentComplete()).isNull();
		assertThat(primary.filesFound()).isEqualTo(37);
		assertThat(primary.totalExpected()).isNull();
	}

	/** The same number the execution screen shows, because it is the same code. */
	@Test
	void thePercentageIsTheOneTheExecutionScreenWouldShow() {
		Execution execution = execution(ExecutionType.INVENTORY, ExecutionStatus.RUNNING, 0, NOON);

		execution.setFilesFound(43);
		execution.setTotalExpected(120);

		when(executionRepository.findByStatusIn(any())).thenReturn(List.of(execution));

		assertThat(service.current().primary().percentComplete())
				.isEqualTo(mapper().toResponse(execution).percentComplete());
	}

	/**
	 * Counting finished items is not the same as being finished. A geodata update
	 * counts three administrative levels and reads 3 of 3 - 100% - while it is
	 * still writing the supplemental territory files, and the only thing that can
	 * say so is how far into the current step it is.
	 */
	@Test
	void theStepStillBeingWorkedOnIsReportedBesideTheOverallCount() {
		Execution execution = execution(ExecutionType.GEO_DATASET_UPDATE, ExecutionStatus.RUNNING, 0, NOON);

		execution.setFilesFound(3);
		execution.setTotalExpected(3);
		execution.setCurrentItemPercent(37);

		when(executionRepository.findByStatusIn(any())).thenReturn(List.of(execution));

		ExecutionActivity primary = service.current().primary();

		assertThat(primary.percentComplete()).isEqualTo(100.0);
		assertThat(primary.currentItemPercent()).isEqualTo(37);
	}

	/** Nothing to report is reported as nothing, not as a step sitting at zero. */
	@Test
	void workThatReportsNoStepSaysSoRatherThanReportingZero() {
		Execution execution = execution(ExecutionType.INVENTORY, ExecutionStatus.RUNNING, 0, NOON);

		execution.setCurrentItemPercent(null);

		when(executionRepository.findByStatusIn(any())).thenReturn(List.of(execution));

		assertThat(service.current().primary().currentItemPercent()).isNull();
	}

	/**
	 * A worker that died leaves its row RUNNING until the reclaim finds it, but it
	 * stops renewing anything - so a step percentage on a row that is no longer
	 * running is stale by definition. The mapper is where that guarantee lives and
	 * the banner inherits it instead of restating it.
	 */
	@Test
	void aStepPercentageOnWorkThatIsNotRunningNeverReachesTheBanner() {
		Execution execution = execution(ExecutionType.GEO_DATASET_UPDATE, ExecutionStatus.PENDING, 0, NOON);

		execution.setCurrentItemPercent(80);

		when(executionRepository.findByStatusIn(any())).thenReturn(List.of(execution));

		assertThat(service.current().primary().currentItemPercent()).isNull();
	}

	/**
	 * Asking something to stop does not stop it, and the row keeps running until it
	 * notices. Reporting it as merely running would make the button look ignored.
	 */
	@Test
	void workThatWasAskedToStopIsStillRunningAndSaysSo() {
		Execution execution = execution(ExecutionType.CONVERSION, ExecutionStatus.RUNNING, 0, NOON);

		execution.setCancelRequested(true);

		when(executionRepository.findByStatusIn(any())).thenReturn(List.of(execution));

		ExecutionActivity primary = service.current().primary();

		assertThat(primary.cancelRequested()).isTrue();
		assertThat(primary.status()).isEqualTo("RUNNING");
	}

	@Test
	void theLinkLeadsToTheExecutionBeingDescribed() {
		Execution execution = execution(ExecutionType.INVENTORY, ExecutionStatus.RUNNING, 0, NOON);

		when(executionRepository.findByStatusIn(any())).thenReturn(List.of(execution));

		ExecutionActivity primary = service.current().primary();

		assertThat(primary.executionId()).isEqualTo(execution.getPublicId());
		assertThat(primary.href()).isEqualTo("/app/executions/" + execution.getPublicId());
	}

	/** Rows written before public ids exist still have somewhere to link to. */
	@Test
	void aRowThatPredatesPublicIdsStillLinksSomewhere() {
		Execution execution = execution(ExecutionType.INVENTORY, ExecutionStatus.RUNNING, 0, NOON);

		execution.setPublicId(null);

		when(executionRepository.findByStatusIn(any())).thenReturn(List.of(execution));

		ExecutionActivity primary = service.current().primary();

		assertThat(primary.executionId()).isEqualTo(UuidV7.fromLegacy(execution.getId()));
		assertThat(primary.href()).isEqualTo("/app/executions/" + UuidV7.fromLegacy(execution.getId()));
	}

	private ExecutionLabels labels() {
		ExecutionLabels labels = new ExecutionLabels();

		labels.setMessageSource(messageSource());

		return labels;
	}

	private ExecutionMapper mapper() {
		ExecutionMapper mapper = new ExecutionMapper(new ExecutionMessageCodec(new ObjectMapper()), labels());

		mapper.setMessageSource(messageSource());

		return mapper;
	}

	private MessageSource messageSource() {
		ResourceBundleMessageSource source = new ResourceBundleMessageSource();

		source.setBasename("messages");
		source.setDefaultEncoding("UTF-8");
		// Mirror production (spring.messages.fallback-to-system-locale=false): a pt-BR
		// request must fall back to the pt-BR base bundle, never to the runner's system
		// locale. Without this the CI's English default resolves messages_en for pt-BR.
		source.setFallbackToSystemLocale(false);

		return source;
	}

	private Execution execution(ExecutionType type, ExecutionStatus status, Integer priority, LocalDateTime createdAt) {
		return Execution.builder().id(nextId++).publicId(UUID.randomUUID()).executionType(type).status(status)
				.priority(priority).createdAt(createdAt).sourcePath("C:\\midia").filesFound(0).filesAnalyzed(0)
				.cacheHits(0).filesMoved(0).simulatedFiles(0).errors(0).build();
	}
}