package br.com.jorgemelo.nimbusfilemanager.update.infrastructure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.UpdateProperties;
import br.com.jorgemelo.nimbusfilemanager.update.application.UpdateCheckService;
import br.com.jorgemelo.nimbusfilemanager.update.application.UpdateInstallProgress;
import br.com.jorgemelo.nimbusfilemanager.update.application.UpdateInstallService;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.AvailableUpdate;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.PublishedRelease;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.UpdateStatus;

/**
 * The screen renders what this assembles and decides nothing for itself, so
 * every flag it would otherwise have to work out - whether to offer the check,
 * whether to offer the install - has to be right here.
 */
class UpdateSettingsModelTest {

	private final UpdateCheckService updateCheckService = mock(UpdateCheckService.class);
	private final UpdateInstallService updateInstallService = mock(UpdateInstallService.class);

	/**
	 * The suite has no manifest, which is the same state as a run from the IDE:
	 * there is no version to compare, so the check is not offered rather than
	 * offered and always answering "nothing found".
	 */
	@Test
	void doesNotOfferTheCheckToARunWithNoVersionOfItsOwn() {
		when(updateCheckService.available()).thenReturn(Optional.empty());
		when(updateInstallService.canInstall()).thenReturn(false);

		UpdateStatus status = assemble(properties(true));

		Assertions.assertThat(status.canCheck()).isFalse();
		Assertions.assertThat(status.installed()).isNull();
		Assertions.assertThat(status.available()).isFalse();
		Assertions.assertThat(status.published()).isNull();
		Assertions.assertThat(status.page()).isNull();
	}

	@Test
	void doesNotOfferTheCheckWhenItIsSwitchedOff() {
		when(updateCheckService.available()).thenReturn(Optional.empty());

		Assertions.assertThat(assemble(properties(false)).canCheck()).isFalse();
	}

	@Test
	void carriesWhatWasFoundToTheScreen() {
		when(updateCheckService.available()).thenReturn(Optional.of(update()));
		when(updateInstallService.canInstall()).thenReturn(true);

		UpdateStatus status = assemble(properties(true));

		Assertions.assertThat(status.available()).isTrue();
		Assertions.assertThat(status.published()).isEqualTo("v6.1.0.160");
		Assertions.assertThat(status.page()).isEqualTo("https://example.invalid/page");
		Assertions.assertThat(status.canInstall()).isTrue();
	}

	private static UpdateProperties properties(boolean enabled) {
		return new UpdateProperties(enabled, "https://example.invalid", Duration.ofMinutes(15));
	}

	private UpdateStatus assemble(UpdateProperties properties) {
		Model model = new ConcurrentModel();

		new UpdateSettingsModel(updateCheckService, updateInstallService, properties, new UpdateInstallProgress())
				.addTo(model, null);

		return (UpdateStatus) model.getAttribute("updateStatus");
	}

	private static AvailableUpdate update() {
		return new AvailableUpdate("6.0.0.147", "v6.1.0.160",
				new PublishedRelease("v6.1.0.160", "https://example.invalid/page", "a.msi",
						"https://example.invalid/a.msi", "https://example.invalid/a.msi.sha256", 10L));
	}
}