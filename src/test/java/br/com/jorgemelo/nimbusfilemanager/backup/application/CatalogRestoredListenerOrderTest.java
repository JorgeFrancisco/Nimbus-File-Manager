package br.com.jorgemelo.nimbusfilemanager.backup.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.OrderUtils;

import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.CatalogRestored;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.LocationCacheRefresh;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.InventoryWatchRefresh;
import br.com.jorgemelo.nimbusfilemanager.settings.application.SettingsCacheRefresh;

/**
 * The settings cache has to be dropped before anything reads a setting again.
 *
 * <p>
 * The first restore on a real installation got this backwards: the watcher
 * reconfigured itself from the folder of the installation being replaced,
 * because the order had been declared on the classes - and Spring resolves an
 * {@code @EventListener}'s order from the method, ignoring the class. Nothing
 * failed; the watcher simply watched nothing.
 */
class CatalogRestoredListenerOrderTest {

	@Test
	void dropsTheSettingsCacheBeforeAnyoneReadsASettingAgain() throws Exception {
		assertThat(order(SettingsCacheRefresh.class)).isLessThan(order(InventoryWatchRefresh.class));
	}

	/**
	 * Reads it the way Spring does. Asserting the annotation is on the method is
	 * the point: on the class it compiles, reads well and does nothing.
	 */
	private int order(Class<?> listener) throws NoSuchMethodException {
		Method method = listener.getMethod("onCatalogRestored", CatalogRestored.class);

		Integer declared = OrderUtils.getOrder(method);

		assertThat(declared).as("%s must declare its order on the method", listener.getSimpleName()).isNotNull();

		return declared;
	}

	@Test
	void pinsTheOrderOfEveryListenerThatReactsToARestore() throws Exception {
		assertThat(order(LocationCacheRefresh.class)).isGreaterThan(order(SettingsCacheRefresh.class));
	}
}