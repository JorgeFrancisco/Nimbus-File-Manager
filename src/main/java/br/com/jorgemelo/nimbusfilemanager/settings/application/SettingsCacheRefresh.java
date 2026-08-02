package br.com.jorgemelo.nimbusfilemanager.settings.application;

import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.CatalogRestored;
import lombok.extern.slf4j.Slf4j;

/**
 * Drops the cached settings after a restore replaced the table they came from.
 *
 * <p>
 * The cache is only evicted when this application writes a setting, which a
 * restore never does - it rewrites the rows underneath. The visible cost was
 * the welcome wizard opening again on a freshly restored installation: the
 * watched folder had been read into memory while the table was still empty, and
 * every later read answered from that.
 */
@Slf4j
@Component
public class SettingsCacheRefresh {

	private final AppSettingService appSettingService;

	public SettingsCacheRefresh(AppSettingService appSettingService) {
		this.appSettingService = appSettingService;
	}

	/**
	 * First of the listeners, and the annotation sits on the method because that
	 * is where Spring reads it for an {@code @EventListener} - on the class it is
	 * ignored. That is how the watcher came to reconfigure itself from the very
	 * values this eviction was about to replace, and it did so silently.
	 */
	@Order(Ordered.HIGHEST_PRECEDENCE)
	@EventListener
	public void onCatalogRestored(CatalogRestored event) {
		appSettingService.evictAll();

		log.info("Settings cache dropped after restoring {}", event.name());
	}
}