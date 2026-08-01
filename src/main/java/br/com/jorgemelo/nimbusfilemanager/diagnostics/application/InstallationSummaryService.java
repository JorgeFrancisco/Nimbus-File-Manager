package br.com.jorgemelo.nimbusfilemanager.diagnostics.application;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.OfflineGeoDataset;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.OfflineGeoDatasetStatus;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolInstaller;
import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.ExternalToolStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * The identity card of one installation: version, platform, external tools,
 * geographic dataset and how much has been catalogued. It answers the questions
 * a support conversation always starts with, in the order they are asked, so
 * nobody has to interview the operator to find out what they are running.
 */
@Service
public class InstallationSummaryService {

	private static final String UNKNOWN = "unknown";

	private final ObjectProvider<BuildProperties> buildProperties;
	private final ExternalToolInstaller externalToolInstaller;
	private final OfflineGeoDataset offlineGeoDataset;
	private final CatalogFileRepository catalogFileRepository;
	private final ExecutionRepository executionRepository;
	private final Clock clock;

	public InstallationSummaryService(ObjectProvider<BuildProperties> buildProperties,
			ExternalToolInstaller externalToolInstaller, OfflineGeoDataset offlineGeoDataset,
			CatalogFileRepository catalogFileRepository, ExecutionRepository executionRepository, Clock clock) {
		this.buildProperties = buildProperties;
		this.externalToolInstaller = externalToolInstaller;
		this.offlineGeoDataset = offlineGeoDataset;
		this.catalogFileRepository = catalogFileRepository;
		this.executionRepository = executionRepository;
		this.clock = clock;
	}

	public String summary() {
		StringBuilder text = new StringBuilder();

		text.append("Nimbus File Manager - diagnostics\n");
		line(text, "Generated at", LocalDateTime.now(clock).toString());
		line(text, "Application version", version());

		text.append("\n[Platform]\n");
		line(text, "Java", System.getProperty("java.version", UNKNOWN));
		line(text, "Operating system", System.getProperty("os.name", UNKNOWN) + " "
				+ System.getProperty("os.version", "") + " (" + System.getProperty("os.arch", UNKNOWN) + ")");
		line(text, "Application time zone", clock.getZone().getId());
		line(text, "Default locale", Locale.getDefault().toLanguageTag());

		appendTools(text);
		appendGeoDataset(text);

		text.append("\n[Catalog]\n");
		line(text, "Files catalogued", Long.toString(catalogFileRepository.count()));
		line(text, "Executions recorded", Long.toString(executionRepository.count()));

		return text.toString();
	}

	private void appendTools(StringBuilder text) {
		ExternalToolStatus tools = externalToolInstaller.status();

		text.append("\n[External tools]\n");
		line(text, "FFmpeg", (tools.ffmpegAvailable() ? "available" : "MISSING") + " at " + tools.ffmpegPath());
		line(text, "FFprobe", (tools.ffprobeAvailable() ? "available" : "MISSING") + " at " + tools.ffprobePath());
		line(text, "Version", tools.version() == null ? UNKNOWN : tools.version());
		line(text, "Installed by the application", Boolean.toString(tools.bundled()));
	}

	private void appendGeoDataset(StringBuilder text) {
		OfflineGeoDatasetStatus geo = offlineGeoDataset.status();

		text.append("\n[Geographic dataset]\n");
		line(text, "Available", Boolean.toString(geo.available()));
		line(text, "Version", geo.version() == null ? UNKNOWN : geo.version());
		line(text, "Imported boundaries", Long.toString(geo.importedRecords()));
		line(text, "Last error", geo.lastError() == null ? "none" : geo.lastError());
	}

	/**
	 * Absent when the application runs from an exploded build with no
	 * {@code build-info.properties} - an IDE launch, typically. Reported as unknown
	 * rather than failing the export over it.
	 */
	private String version() {
		BuildProperties properties = buildProperties.getIfAvailable();

		return properties == null ? UNKNOWN : properties.getVersion();
	}

	private void line(StringBuilder text, String label, String value) {
		text.append(label).append(": ").append(value).append('\n');
	}
}