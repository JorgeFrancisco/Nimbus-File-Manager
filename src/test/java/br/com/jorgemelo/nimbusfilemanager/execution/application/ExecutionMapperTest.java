package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionStepResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ExecutionLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.ExecutionStep;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;

class ExecutionMapperTest {

	private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

	private final ExecutionMessageCodec codec = new ExecutionMessageCodec(new ObjectMapper());

	@AfterEach
	void resetLocale() {
		LocaleContextHolder.resetLocaleContext();
	}

	@Test
	void resolvesMessageCodeInPortugueseAndEnglish() {
		Execution execution = execution(ExecutionStatus.STARTED, ExecutionMessages.INVENTORY_STARTED, null);

		LocaleContextHolder.setLocale(PT_BR);
		assertThat(mapper().toResponse(execution).message()).isEqualTo("Inventário iniciado.");

		LocaleContextHolder.setLocale(Locale.ENGLISH);
		assertThat(mapper().toResponse(execution).message()).isEqualTo("Inventory started.");
	}

	@Test
	void resolvesNumericArgumentsForCountMessages() {
		Execution execution = execution(ExecutionStatus.FINISHED, ExecutionMessages.ORGANIZATION_FINISHED,
				codec.encode(List.of(5L, 2L, 1L)));

		LocaleContextHolder.setLocale(PT_BR);
		assertThat(mapper().toResponse(execution).message())
				.isEqualTo("Organização concluída. movidos=5, ignorados=2, erros=1.");

		LocaleContextHolder.setLocale(Locale.ENGLISH);
		assertThat(mapper().toResponse(execution).message())
				.isEqualTo("Organization completed. moved=5, skipped=2, errors=1.");
	}

	@Test
	void resolvesStatusLabelAndFinishedFlagForTerminalStatus() {
		Execution execution = execution(ExecutionStatus.FINISHED, ExecutionMessages.INVENTORY_COMPLETED, null);

		LocaleContextHolder.setLocale(PT_BR);

		ExecutionResponse response = mapper().toResponse(execution);

		assertThat(response.statusLabel()).isEqualTo("Concluído");
		assertThat(response.finished()).isTrue();
	}

	@Test
	void reportsNotFinishedForActiveStatus() {
		Execution execution = execution(ExecutionStatus.PROCESSING_FILES, ExecutionMessages.PROCESSING_FILES, null);

		LocaleContextHolder.setLocale(Locale.ENGLISH);

		ExecutionResponse response = mapper().toResponse(execution);

		assertThat(response.statusLabel()).isEqualTo("Processing files");
		assertThat(response.finished()).isFalse();
	}

	@Test
	void resolvesEveryStatusLabelInPortuguese() {
		LocaleContextHolder.setLocale(PT_BR);

		assertThat(labelOf(ExecutionStatus.STARTED)).isEqualTo("Iniciando");
		assertThat(labelOf(ExecutionStatus.SCANNING_FILES)).isEqualTo("Contando arquivos");
		assertThat(labelOf(ExecutionStatus.PROCESSING_FILES)).isEqualTo("Processando arquivos");
		assertThat(labelOf(ExecutionStatus.FINISHED)).isEqualTo("Concluído");
		assertThat(labelOf(ExecutionStatus.FINISHED_WITH_ERRORS)).isEqualTo("Concluído com erros");
		assertThat(labelOf(ExecutionStatus.INTERRUPTED)).isEqualTo("Interrompido");
		assertThat(labelOf(ExecutionStatus.ERROR)).isEqualTo("Erro");
		assertThat(labelOf(ExecutionStatus.CANCELLED)).isEqualTo("Cancelado");
		assertThat(labelOf(ExecutionStatus.REJECTED)).isEqualTo("Rejeitado");
	}

	@Test
	void resolvesEveryStatusLabelInEnglish() {
		LocaleContextHolder.setLocale(Locale.ENGLISH);

		assertThat(labelOf(ExecutionStatus.STARTED)).isEqualTo("Starting");
		assertThat(labelOf(ExecutionStatus.SCANNING_FILES)).isEqualTo("Counting files");
		assertThat(labelOf(ExecutionStatus.PROCESSING_FILES)).isEqualTo("Processing files");
		assertThat(labelOf(ExecutionStatus.FINISHED)).isEqualTo("Completed");
		assertThat(labelOf(ExecutionStatus.FINISHED_WITH_ERRORS)).isEqualTo("Completed with errors");
		assertThat(labelOf(ExecutionStatus.INTERRUPTED)).isEqualTo("Interrupted");
		assertThat(labelOf(ExecutionStatus.ERROR)).isEqualTo("Error");
		assertThat(labelOf(ExecutionStatus.CANCELLED)).isEqualTo("Cancelled");
		assertThat(labelOf(ExecutionStatus.REJECTED)).isEqualTo("Rejected");
	}

	@Test
	void marksEveryTerminalStatusAsFinishedAndActiveStatusesAsNotFinished() {
		assertThat(mapper().toResponse(execution(ExecutionStatus.FINISHED_WITH_ERRORS, null, null)).finished())
				.isTrue();
		assertThat(mapper().toResponse(execution(ExecutionStatus.INTERRUPTED, null, null)).finished()).isTrue();
		assertThat(mapper().toResponse(execution(ExecutionStatus.ERROR, null, null)).finished()).isTrue();
		assertThat(mapper().toResponse(execution(ExecutionStatus.CANCELLED, null, null)).finished()).isTrue();
		assertThat(mapper().toResponse(execution(ExecutionStatus.REJECTED, null, null)).finished()).isTrue();
		assertThat(mapper().toResponse(execution(ExecutionStatus.STARTED, null, null)).finished()).isFalse();
		assertThat(mapper().toResponse(execution(ExecutionStatus.SCANNING_FILES, null, null)).finished()).isFalse();
	}

	@Test
	void fallsBackToLegacyMessageVerbatimWhenNoCodeIsPresent() {
		Execution execution = execution(ExecutionStatus.FINISHED, null, null);
		execution.setStatusMessage(StatusMessage.raw("Legacy free text that predates codes."));

		LocaleContextHolder.setLocale(PT_BR);

		assertThat(mapper().toResponse(execution).message()).isEqualTo("Legacy free text that predates codes.");
	}

	@Test
	void resolvesStepMessageCodeWithArguments() {
		Execution execution = execution(ExecutionStatus.PROCESSING_FILES, null, null);

		ExecutionStep step = ExecutionStep.builder().id(10L).execution(execution)
				.stepType(ExecutionStepType.PROGRESS_UPDATED)
				.statusMessage(StatusMessage.coded(ExecutionMessages.PROCESSING_FILE,
						codec.encode(List.of("C:/media/photo.jpg"))))
				.createdAt(LocalDateTime.now()).build();

		LocaleContextHolder.setLocale(PT_BR);

		ExecutionStepResponse response = mapper().toStepResponse(step);

		assertThat(response.message()).isEqualTo("Processando arquivo: C:/media/photo.jpg");
	}

	@Test
	void resolvesEveryTypeLabelInPortuguese() {
		LocaleContextHolder.setLocale(PT_BR);

		assertThat(typeLabelOf(ExecutionType.INVENTORY)).isEqualTo("Inventário");
		assertThat(typeLabelOf(ExecutionType.ORGANIZATION)).isEqualTo("Organização");
		assertThat(typeLabelOf(ExecutionType.UNDO)).isEqualTo("Desfazer");
		assertThat(typeLabelOf(ExecutionType.EXPORT)).isEqualTo("Exportação");
		assertThat(typeLabelOf(ExecutionType.SUMMARY)).isEqualTo("Resumo");
		assertThat(typeLabelOf(ExecutionType.DEDUP_DELETE)).isEqualTo("Remoção de duplicados");
		assertThat(typeLabelOf(ExecutionType.RECONCILE)).isEqualTo("Reconciliação");
	}

	@Test
	void resolvesTypeLabelInEnglish() {
		LocaleContextHolder.setLocale(Locale.ENGLISH);

		assertThat(typeLabelOf(ExecutionType.INVENTORY)).isEqualTo("Inventory");
		assertThat(typeLabelOf(ExecutionType.RECONCILE)).isEqualTo("Reconciliation");
	}

	@Test
	void resolvesEveryTriggerLabelWhenPresentAndNullWhenAbsent() {
		LocaleContextHolder.setLocale(PT_BR);

		assertThat(triggerLabelOf(ExecutionTrigger.MANUAL)).isEqualTo("Manual");
		assertThat(triggerLabelOf(ExecutionTrigger.FILE_EVENT)).isEqualTo("Evento de arquivo");
		assertThat(triggerLabelOf(ExecutionTrigger.TIMER)).isEqualTo("Verificação periódica");
		assertThat(triggerLabelOf(null)).isNull();
	}

	private String labelOf(ExecutionStatus status) {
		return mapper().toResponse(execution(status, null, null)).statusLabel();
	}

	private String typeLabelOf(ExecutionType type) {
		return mapper().toResponse(executionOfType(type)).typeLabel();
	}

	private String triggerLabelOf(ExecutionTrigger trigger) {
		return mapper().toResponse(executionWithTrigger(trigger)).triggerLabel();
	}

	private Execution executionOfType(ExecutionType type) {
		return Execution.builder().id(1L).executionType(type).status(ExecutionStatus.FINISHED)
				.startedAt(LocalDateTime.now()).filesFound(0).filesAnalyzed(0).cacheHits(0).filesMoved(0)
				.simulatedFiles(0).errors(0).build();
	}

	private Execution executionWithTrigger(ExecutionTrigger trigger) {
		return Execution.builder().id(1L).executionType(ExecutionType.RECONCILE).triggerEvent(trigger)
				.status(ExecutionStatus.FINISHED).startedAt(LocalDateTime.now()).filesFound(0).filesAnalyzed(0)
				.cacheHits(0).filesMoved(0).simulatedFiles(0).errors(0).build();
	}

	private ExecutionMapper mapper() {
		MessageSource source = messageSource();

		ExecutionLabels labels = new ExecutionLabels();
		labels.setMessageSource(source);

		ExecutionMapper mapper = new ExecutionMapper(codec, labels);
		mapper.setMessageSource(source);

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

	private Execution execution(ExecutionStatus status, String messageCode, String messageArgs) {
		return Execution.builder().id(1L).executionType(ExecutionType.INVENTORY).status(status)
				.startedAt(LocalDateTime.now()).filesFound(0).filesAnalyzed(0).cacheHits(0).filesMoved(0)
				.simulatedFiles(0).errors(0).statusMessage(StatusMessage.coded(messageCode, messageArgs)).build();
	}
}