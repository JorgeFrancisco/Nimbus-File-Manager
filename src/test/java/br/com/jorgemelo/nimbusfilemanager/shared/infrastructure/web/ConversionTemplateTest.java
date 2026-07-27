package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Guards the screen contracts of the conversion page and its script. */
class ConversionTemplateTest {

	private static final Path SCRIPT = Path.of("src/main/resources/static/js/pages/conversion.js");

	/**
	 * The batch disables every field while it runs, and a disabled field is left out
	 * of {@code FormData}. Serialising the form therefore sent the options as nulls,
	 * and the server replaced each one with its default - so pressing convert
	 * silently rewrote the user's saved quality, audio, disposition and name affix
	 * back to the recommended combination. Reading the checked control survives the
	 * disabling, because a disabled radio still reports what it holds.
	 */
	@Test
	void optionsAreReadFromTheControlsSoDisablingTheFormNeverRewritesThem() throws Exception {
		String javascript = Files.readString(SCRIPT);

		assertThat(javascript).contains(":checked").contains("function pickedValue")
				.doesNotContain("new FormData(optionsForm)");
	}

	/**
	 * A converted file stops being a candidate, so the screen has to come back from
	 * the server when the batch ends - it used to keep listing the files it had
	 * just converted until the user reloaded by hand. The report is what a reload
	 * would destroy, so it crosses over in session storage.
	 */
	@Test
	void theListIsRefreshedWhenTheBatchEndsAndTheReportSurvivesIt() throws Exception {
		String javascript = Files.readString(SCRIPT);

		assertThat(javascript).contains("location.reload").contains("sessionStorage.setItem(REPORT_KEY")
				.contains("function restoreReport");
	}

	/**
	 * A failed poll is not a failed batch: the conversion keeps running on the
	 * server, and dropping the watch left the screen frozen with no final report.
	 */
	@Test
	void aFailedProgressPollIsRetriedInsteadOfEndingTheWatch() throws Exception {
		String javascript = Files.readString(SCRIPT);

		assertThat(javascript).contains("MAX_POLL_FAILURES").contains("POLL_RETRY_MILLIS");
	}

	/**
	 * The hardware profile only exists where the machine proved it can encode with
	 * it, so the screen offers it conditionally and the value must match the enum
	 * the server binds.
	 */
	@Test
	void theHardwareProfileIsOfferedOnlyWhenTheMachineHasAnEncoder() throws Exception {
		String html = Files.readString(Path.of("src/main/resources/templates/app/conversion.html"));

		assertThat(html).contains("th:if=\"${hardwareEncoder}\"").contains("value=\"FAST_HIGH_QUALITY\"")
				.contains("value=\"FAST_BALANCED\"").contains("#{conversion.quality.fastBalanced}");
	}
}