package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.util.Locale;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.EtaEstimate;

/**
 * The one wording for a remaining time, in both languages the product ships.
 *
 * <p>
 * There were three vocabularies before this: one that could say hours, one that
 * could only say minutes - and so reported a five-hour backlog as "300 min" -
 * and a third written inline in a template, three times over. They disagreed
 * about the same quantity, which is what made a reader distrust all of them.
 */
class EtaLabelsTest {

	private final EtaLabels labels = labels();

	@AfterEach
	void restoreLocale() {
		LocaleContextHolder.resetLocaleContext();
	}

	@Test
	void underAMinuteSaysSoRatherThanCountingSeconds() {
		Assertions.assertThat(labels.label(EtaEstimate.of(40))).isEqualTo("menos de 1 min restante");
	}

	@Test
	void minutesReadAsMinutes() {
		Assertions.assertThat(labels.label(EtaEstimate.of(300))).isEqualTo("aprox. 5 min restantes");
	}

	/**
	 * The form the old backend vocabulary did not have at all: a five-hour backlog
	 * had to be announced in minutes, which reads as a number nobody can hold.
	 */
	@Test
	void hoursReadAsHours() {
		Assertions.assertThat(labels.label(EtaEstimate.of(18_000))).isEqualTo("aprox. 5 h restantes");
	}

	@Test
	void anHourAndAHalfCarriesBothParts() {
		Assertions.assertThat(labels.label(EtaEstimate.of(5_400))).isEqualTo("aprox. 1 h 30 min restantes");
	}

	@Test
	void aMeasurementNotTakenYetSaysItIsBeingTaken() {
		Assertions.assertThat(labels.label(EtaEstimate.calculating())).isEqualTo("calculando tempo estimado…");
	}

	/**
	 * Work with no honest denominator says nothing, and empty is what lets a
	 * caller append it blindly. "Calculating" here would promise an estimate that
	 * is never coming.
	 */
	@Test
	void workWithoutAnEstimateSaysNothingAtAll() {
		Assertions.assertThat(labels.label(EtaEstimate.notApplicable())).isEmpty();
		Assertions.assertThat(labels.label(null)).isEmpty();
	}

	/** Every form exists in English too, and the build breaks if one does not. */
	@Test
	void theSameSentencesExistInEnglish() {
		LocaleContextHolder.setLocale(Locale.ENGLISH);

		Assertions.assertThat(labels.label(EtaEstimate.of(40))).isEqualTo("less than 1 min left");
		Assertions.assertThat(labels.label(EtaEstimate.of(300))).isEqualTo("about 5 min left");
		Assertions.assertThat(labels.label(EtaEstimate.of(18_000))).isEqualTo("about 5 h left");
		Assertions.assertThat(labels.label(EtaEstimate.of(5_400))).isEqualTo("about 1 h 30 min left");
		Assertions.assertThat(labels.label(EtaEstimate.calculating())).isEqualTo("estimating time left…");
	}

	private EtaLabels labels() {
		EtaLabels created = new EtaLabels();

		created.setMessageSource(messageSource());

		return created;
	}

	private MessageSource messageSource() {
		ResourceBundleMessageSource source = new ResourceBundleMessageSource();

		source.setBasename("messages");
		source.setDefaultEncoding("UTF-8");
		source.setFallbackToSystemLocale(false);

		return source;
	}
}