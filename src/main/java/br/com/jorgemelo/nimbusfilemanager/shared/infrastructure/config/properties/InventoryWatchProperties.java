package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Boot-time configuration of the automatic folder-watch poll loop. Kept in code
 * (not app_setting) because it must be known before the persistence layer
 * exists: the surefire test JVM sets {@code enabled=false} so the 500ms DB poll
 * never runs in {@code @SpringBootTest} contexts that don't exercise it (it
 * would only race the Testcontainers/Hikari teardown at JVM exit). Production
 * keeps the default.
 */
@ConfigurationProperties(prefix = "nimbus-file-manager.inventory.watch")
public class InventoryWatchProperties {

	/** Whether the folder-watch poll loop is scheduled. */
	private boolean enabled = true;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}
}