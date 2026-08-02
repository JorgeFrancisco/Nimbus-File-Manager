package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Boot-time configuration of the administrative-boundary dataset. The download
 * URLs live in app_setting (editable on the Settings screen, seeded by
 * {@code AppSettingService} with the official geoBoundaries CGAZ defaults);
 * only what must exist before the database does stays here: the optional local
 * folder for development/tests and the provider labels persisted with the data.
 * Security limits remain code-owned.
 */
@ConfigurationProperties(prefix = "nimbus-file-manager.location.boundary")
public class BoundaryDatasetProperties {

	/**
	 * Optional local folder holding geoBoundariesCGAZ_ADM0/1/2.geojson. When set
	 * and the files exist, they are used instead of downloading (dev/tests).
	 */
	private String localDir = "";

	/**
	 * Whether this run keeps the dataset current by itself. Off means the timer is
	 * never scheduled - nothing is acquired, nothing is refreshed, and no thread
	 * exists to tick.
	 *
	 * <p>
	 * Distinct from the {@code auto-update-enabled} setting on the Settings screen,
	 * which decides whether a running timer acts on a given day. This one decides
	 * whether the timer runs at all, and it is the switch a run with no business on
	 * the network needs: the suite, where every {@code @SpringBootTest} would
	 * otherwise find the dataset missing and take the "acquire it now, whatever the
	 * hour" branch - several hundred megabytes, once per context, into a folder
	 * {@code mvn clean} then deletes.
	 */
	private boolean autoUpdate = true;

	/** Provider label shown on the admin screen. */
	private String providerLabel = "geoBoundaries CGAZ";

	/** License shown on the admin screen (attribution required). */
	private String license = "CC BY 4.0 - geoBoundaries (geoBoundaries.org)";

	/** Source tag persisted with each boundary row. */
	private String sourceTag = "geoBoundaries";

	public String getLocalDir() {
		return localDir;
	}

	public void setLocalDir(String localDir) {
		this.localDir = localDir;
	}

	public boolean isAutoUpdate() {
		return autoUpdate;
	}

	public void setAutoUpdate(boolean autoUpdate) {
		this.autoUpdate = autoUpdate;
	}

	public String getProviderLabel() {
		return providerLabel;
	}

	public void setProviderLabel(String providerLabel) {
		this.providerLabel = providerLabel;
	}

	public String getLicense() {
		return license;
	}

	public void setLicense(String license) {
		this.license = license;
	}

	public String getSourceTag() {
		return sourceTag;
	}

	public void setSourceTag(String sourceTag) {
		this.sourceTag = sourceTag;
	}
}