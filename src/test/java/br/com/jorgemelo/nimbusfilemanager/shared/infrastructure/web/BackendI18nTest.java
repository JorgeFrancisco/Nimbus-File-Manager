package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Guards representative browser-facing backend paths against fixed-locale
 * regressions.
 */
class BackendI18nTest {

	@Test
	void controllersAndServicesResolveDisplayedMessagesByKey() throws Exception {
		assertThat(read(
				"src/main/java/br/com/jorgemelo/nimbusfilemanager/settings/infrastructure/web/SettingsWebController.java"))
						.contains("extends LocalizedComponent", "backend.settings.preferencesUpdated");
		assertThat(read(
				"src/main/java/br/com/jorgemelo/nimbusfilemanager/settings/infrastructure/web/SettingsParameterWebController.java"))
						.contains("extends LocalizedComponent", "backend.settings.updated");
		assertThat(read(
				"src/main/java/br/com/jorgemelo/nimbusfilemanager/geolocation/infrastructure/web/SettingsGeodataWebController.java"))
						.contains("extends LocalizedComponent", "backend.settings.cacheCleared");
		assertThat(read(
				"src/main/java/br/com/jorgemelo/nimbusfilemanager/organization/infrastructure/web/OrganizationWebController.java"))
						.contains("extends LocalizedComponent", "backend.organization.previewNotFound");
		assertThat(
				read("src/main/java/br/com/jorgemelo/nimbusfilemanager/quarantine/application/QuarantineService.java"))
						.contains("extends LocalizedComponent", "backend.quarantine.batchCompleted");
		assertThat(read(
				"src/main/java/br/com/jorgemelo/nimbusfilemanager/security/application/AppUserAccountService.java"))
						.contains("extends LocalizedComponent", "backend.account.tokenExpired");
	}

	@Test
	void duplicateAsyncDeletionCarriesRequestLocaleToWorkerThread() throws Exception {
		String source = read(
				"src/main/java/br/com/jorgemelo/nimbusfilemanager/duplicate/application/DuplicateDeletionAsyncRunner.java");

		assertThat(source).contains("requestedLocale = LocaleContextHolder.getLocale()",
				"LocaleContextHolder.setLocale(requestedLocale)", "backend.duplicates.deletionFailed");
	}

	private String read(String path) throws Exception {
		return Files.readString(Path.of(path));
	}
}