package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Guards two things the welcome screen has to get right: the physical-only
 * policy, and that a restore reports itself while it runs.
 */
class OnboardingTemplateTest {

	private static final Path ONBOARDING = Path.of("src/main/resources/templates/app/onboarding.html");

	@Test
	void onboardingScreenHasNoFollowLinksOption() throws Exception {
		String html = Files.readString(ONBOARDING);

		assertThat(html).doesNotContain("followLinks").doesNotContain("> Links<");
	}

	/**
	 * A restore takes minutes, and the first version of this screen asked the
	 * person to keep pressing refresh. The panel drives the shared auto-refresh
	 * instead - which, once the restore configures the library, follows the
	 * redirect to the dashboard by itself.
	 */
	@Test
	void restorePanelRefreshesItselfWhileTheRestoreRuns() throws Exception {
		String html = Files.readString(ONBOARDING);

		assertThat(html).contains("id=\"onboardingRestore\"").contains("data-refresh-ms=${restoring}");
	}
}