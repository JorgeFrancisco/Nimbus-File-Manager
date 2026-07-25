package br.com.jorgemelo.nimbusfilemanager.map.infrastructure.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;

/**
 * Renders the media map page. The map background (tile server URL, attribution,
 * max zoom) and the on/off flag come from {@link AppSettingService}, so an
 * admin can point the map at another provider or a self-hosted tile server, or
 * disable the screen entirely, without a redeploy. The pins themselves are
 * loaded by {@code map.js} from {@code /api/map/pins}.
 */
@Controller
public class MapWebController {

	private final AppSettingService appSettingService;

	public MapWebController(AppSettingService appSettingService) {
		this.appSettingService = appSettingService;
	}

	@GetMapping("/app/map")
	public String map(Model model) {
		model.addAttribute("mapEnabled", appSettingService.booleanValue(SettingsConstants.MAP_ENABLED, true));
		model.addAttribute("mapTileUrl", appSettingService.stringValue(SettingsConstants.MAP_TILE_URL,
				"https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"));
		model.addAttribute("mapTileAttribution",
				appSettingService.stringValue(SettingsConstants.MAP_TILE_ATTRIBUTION, "© OpenStreetMap contributors"));
		model.addAttribute("mapMaxZoom", appSettingService.intValue(SettingsConstants.MAP_MAX_ZOOM, 19));

		return "app/map";
	}
}