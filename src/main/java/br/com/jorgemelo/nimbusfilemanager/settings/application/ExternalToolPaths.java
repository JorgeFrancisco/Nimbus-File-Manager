package br.com.jorgemelo.nimbusfilemanager.settings.application;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;

/**
 * Where the external tools live, resolved the same way for every caller: the
 * value edited on the Configurações screen wins, and the packaged
 * {@code tools/bin} path from the application properties is the fallback. Every
 * feature that spawns ffmpeg or ffprobe (metadata extraction, perceptual
 * hashing, video conversion) reads the path from here, so a tool that moves is
 * a single change and no service has to carry the settings plumbing just to
 * find a binary.
 */
@Component
public class ExternalToolPaths {

	private final AppSettingService appSettingService;
	private final NimbusFileManagerProperties properties;

	public ExternalToolPaths(AppSettingService appSettingService, NimbusFileManagerProperties properties) {
		this.appSettingService = appSettingService;
		this.properties = properties;
	}

	public String ffmpeg() {
		return appSettingService.stringValue(SettingsConstants.TOOL_FFMPEG, properties.tools().ffmpeg());
	}

	public String ffprobe() {
		return appSettingService.stringValue(SettingsConstants.TOOL_FFPROBE, properties.tools().ffprobe());
	}
}