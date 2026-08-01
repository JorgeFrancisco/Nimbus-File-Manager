package br.com.jorgemelo.nimbusfilemanager.diagnostics.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Properties;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.OfflineGeoDataset;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.OfflineGeoDatasetStatus;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolInstaller;
import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.ExternalToolStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * The first thing anyone reads in a support archive. Every line here answers a
 * question that would otherwise be asked by e-mail, one round trip at a time.
 */
class InstallationSummaryServiceTest {

	@SuppressWarnings("unchecked")
	private final ObjectProvider<BuildProperties> buildProperties = mock(ObjectProvider.class);

	private final ExternalToolInstaller externalToolInstaller = mock(ExternalToolInstaller.class);
	private final OfflineGeoDataset offlineGeoDataset = mock(OfflineGeoDataset.class);
	private final CatalogFileRepository catalogFileRepository = mock(CatalogFileRepository.class);
	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);

	private InstallationSummaryService service() {
		return new InstallationSummaryService(buildProperties, externalToolInstaller, offlineGeoDataset,
				catalogFileRepository, executionRepository, Clock.system(ZoneId.of("America/Sao_Paulo")));
	}

	private void toolsInstalled(boolean ffmpeg, boolean ffprobe) {
		when(externalToolInstaller.status())
				.thenReturn(new ExternalToolStatus(ffmpeg, "tools/ffmpeg/bin/ffmpeg.exe", ffprobe,
				"tools/ffmpeg/bin/ffprobe.exe", "ffmpeg version 8.0", true, true, "C:/app/tools/ffmpeg/bin"));
	}

	private void geoAvailable() {
		when(offlineGeoDataset.status()).thenReturn(
				new OfflineGeoDatasetStatus(true, "CGAZ-2026", 1_500, 2_000_000_000L,
					LocalDateTime.now(), LocalDateTime.now(), "C:/geo", null, "geoBoundaries", "CC BY 4.0"));
	}

	@Test
	void reportsTheVersionThePlatformTheToolsAndTheCatalogSize() {
		Properties properties = new Properties();

		properties.setProperty("group", "br.com.jorgemelo");
		properties.setProperty("artifact", "nimbus-file-manager");
		properties.setProperty("version", "5.28.1.103");

		when(buildProperties.getIfAvailable()).thenReturn(new BuildProperties(properties));

		toolsInstalled(true, true);
		geoAvailable();

		when(catalogFileRepository.count()).thenReturn(12_345L);
		when(executionRepository.count()).thenReturn(42L);

		String summary = service().summary();

		Assertions.assertThat(summary).contains("Application version: 5.28.1.103")
				.contains("Application time zone: America/Sao_Paulo").contains("Files catalogued: 12345")
				.contains("Executions recorded: 42").contains("Imported boundaries: 1500");
	}

	/**
	 * A missing tool has to stand out: it is the single most common cause of "the
	 * conversion does nothing" reports, and reading it as a plain path would hide
	 * it among the rest.
	 */
	@Test
	void callsOutAToolThatIsNotThere() {
		when(buildProperties.getIfAvailable()).thenReturn(null);

		toolsInstalled(false, true);
		geoAvailable();

		String summary = service().summary();

		Assertions.assertThat(summary).contains("FFmpeg: MISSING at tools/ffmpeg/bin/ffmpeg.exe")
				.contains("FFprobe: available at tools/ffmpeg/bin/ffprobe.exe");
	}

	/**
	 * Launched from an IDE there is no build-info, and the version is unknown. The
	 * archive still has to be produced - a summary missing one line beats no
	 * summary at all.
	 */
	@Test
	void reportsAnUnknownVersionWhenTheBuildInfoIsAbsent() {
		when(buildProperties.getIfAvailable()).thenReturn(null);

		// No tool, so no version to report either - the state of a first run.
		when(externalToolInstaller.status()).thenReturn(
				new ExternalToolStatus(false, "ffmpeg", false, "ffprobe", null, false, true,
						"C:/app/tools/ffmpeg/bin"));

		when(offlineGeoDataset.status()).thenReturn(OfflineGeoDatasetStatus.unavailable("C:/geo", "download failed"));

		String summary = service().summary();

		Assertions.assertThat(summary).contains("Application version: unknown").contains("Available: false")
				.contains("Last error: download failed").contains("Version: unknown");
	}
}