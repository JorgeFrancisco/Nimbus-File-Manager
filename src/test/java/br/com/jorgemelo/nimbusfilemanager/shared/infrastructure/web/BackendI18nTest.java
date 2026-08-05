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
						.contains("extends LocalizedComponent", "backend.quarantine.restored");
		assertThat(read(
				"src/main/java/br/com/jorgemelo/nimbusfilemanager/security/application/AppUserAccountService.java"))
						.contains("extends LocalizedComponent", "backend.account.tokenExpired");
	}

	/**
	 * Carrying the request locale into a background thread was how the deletion
	 * message stayed in the user's language. There is no such thread any more: the
	 * work happens in the worker, which has no request behind it, so the message is
	 * stored as a code and localised by whoever reads the row.
	 */
	@Test
	void duplicateDeletionStoresItsOutcomeAsACodeRatherThanText() throws Exception {
		String source = read(
				"src/main/java/br/com/jorgemelo/nimbusfilemanager/duplicate/application/DuplicateDeletionService.java");

		assertThat(source).contains("DuplicateMessages.deletionCompleted", "DuplicateMessages.failed")
				.doesNotContain("StatusMessage.raw");
	}

	/** The same, for the two quarantine batches that moved to the worker. */
	@Test
	void quarantineBatchesStoreTheirOutcomeAsACodeRatherThanText() throws Exception {
		assertThat(
				read("src/main/java/br/com/jorgemelo/nimbusfilemanager/quarantine/application/QuarantineService.java"))
						.contains("QuarantineMessages.batchCompleted", "QuarantineMessages.batchCancelled",
								"QuarantineMessages.batchInterrupted")
						.doesNotContain("StatusMessage.raw");
		assertThat(read(
				"src/main/java/br/com/jorgemelo/nimbusfilemanager/quarantine/application/QuarantinePurgeService.java"))
						.contains("QuarantineMessages.purgeCompleted", "QuarantineMessages.purgeCancelled",
								"QuarantineMessages.purgeInterrupted")
						.doesNotContain("StatusMessage.raw");
	}

	private String read(String path) throws Exception {
		return Files.readString(Path.of(path));
	}
}