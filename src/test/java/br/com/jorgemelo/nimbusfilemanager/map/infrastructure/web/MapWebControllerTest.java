package br.com.jorgemelo.nimbusfilemanager.map.infrastructure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;

class MapWebControllerTest {

	private final AppSettingService appSettingService = mock(AppSettingService.class);
	private final MapWebController controller = new MapWebController(appSettingService);

	@Test
	void mapExposesTheConfigurableTileBackgroundToTheView() {
		when(appSettingService.booleanValue(SettingsConstants.MAP_ENABLED, true)).thenReturn(true);
		when(appSettingService.stringValue(SettingsConstants.MAP_TILE_URL,
				"https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"))
						.thenReturn("https://tiles.example.com/{z}/{x}/{y}.png");
		when(appSettingService.stringValue(SettingsConstants.MAP_TILE_ATTRIBUTION, "© OpenStreetMap contributors"))
				.thenReturn("© Example");
		when(appSettingService.intValue(SettingsConstants.MAP_MAX_ZOOM, 19)).thenReturn(17);

		Model model = new ConcurrentModel();
		String view = controller.map(model);

		Assertions.assertThat(view).isEqualTo("app/map");
		Assertions.assertThat(model.getAttribute("mapEnabled")).isEqualTo(true);
		Assertions.assertThat(model.getAttribute("mapTileUrl")).isEqualTo("https://tiles.example.com/{z}/{x}/{y}.png");
		Assertions.assertThat(model.getAttribute("mapTileAttribution")).isEqualTo("© Example");
		Assertions.assertThat(model.getAttribute("mapMaxZoom")).isEqualTo(17);
	}

	@Test
	void mapCanBeDisabledEntirelyFromSettings() {
		when(appSettingService.booleanValue(SettingsConstants.MAP_ENABLED, true)).thenReturn(false);

		Model model = new ConcurrentModel();

		Assertions.assertThat(controller.map(model)).isEqualTo("app/map");
		Assertions.assertThat(model.getAttribute("mapEnabled")).isEqualTo(false);
	}
}