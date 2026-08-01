package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Guards the banner that reports background work with no execution record. */
class BackgroundJobBannerTest {

	/**
	 * The banner declares {@code display: grid}, and a declared display beats the
	 * browser's own rule for [hidden]. Without the explicit override the banner
	 * rendered as an empty bar showing 0% whenever no job was running - which is
	 * most of the time.
	 */
	@Test
	void theBannerIsActuallyHiddenWhenThereIsNoJob() throws Exception {
		String css = Files.readString(Path.of("src/main/resources/static/css/layout.css"));
		String layout = Files.readString(Path.of("src/main/resources/templates/fragments/layout.html"));

		assertThat(css).contains(".active-execution[hidden]");
		assertThat(layout).contains("th:hidden=\"${backgroundJob == null}\"");
	}

	/**
	 * The backlogs start on their own - after an inventory, after a conversion - so
	 * the banner has to appear without a reload, unlike the execution one.
	 */
	@Test
	void theBannerPollsEvenWhileNothingIsRunning() throws Exception {
		String javascript = Files.readString(Path.of("src/main/resources/static/js/background-job.js"));

		assertThat(javascript).contains("/api/background-job").contains("banner.hidden = true")
				.contains("banner.hidden = false");
	}
}