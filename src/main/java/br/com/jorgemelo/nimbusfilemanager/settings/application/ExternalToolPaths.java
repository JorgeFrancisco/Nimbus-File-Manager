package br.com.jorgemelo.nimbusfilemanager.settings.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.application.CoverageGenerated;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Tools;

/**
 * Where the external tools live, resolved the same way for every caller. Every
 * feature that spawns ffmpeg or ffprobe (metadata extraction, perceptual
 * hashing, video conversion, thumbnails) reads the path from here, so a tool
 * that moves is a single change and no service carries settings plumbing just
 * to find a binary.
 *
 * <p>
 * Three sources, in order: the value edited on the Configurações screen, then
 * the application properties - which is how a container points at
 * {@code /usr/bin} - and finally discovery. Discovery exists because the
 * properties used to ship a Windows path
 * ({@code ./tools/ffmpeg/bin/ffmpeg.exe}), so a clone on Linux or macOS failed
 * until someone found the setting to change, while the Docker image quietly
 * overrode it. The default now names no platform: the bundled binary is used
 * when it is there, and otherwise the bare command, which the operating system
 * resolves through PATH.
 */
@Component
public class ExternalToolPaths {

	/** Where the packaged binaries live, relative to the working directory. */
	private static final Path BUNDLED_DIRECTORY = Path.of("tools", "ffmpeg", "bin");

	private final AppSettingService appSettingService;
	private final NimbusFileManagerProperties properties;
	private final Path bundledDirectory;

	@Autowired
	@CoverageGenerated("Spring wiring: forwards to the constructor every test builds directly")
	public ExternalToolPaths(AppSettingService appSettingService, NimbusFileManagerProperties properties) {
		this(appSettingService, properties, BUNDLED_DIRECTORY);
	}

	/**
	 * Takes the binary directory so a test can point discovery at a folder it
	 * controls; production always uses the packaged one.
	 */
	ExternalToolPaths(AppSettingService appSettingService, NimbusFileManagerProperties properties,
			Path bundledDirectory) {
		this.appSettingService = appSettingService;
		this.properties = properties;
		this.bundledDirectory = bundledDirectory;
	}

	/** Where a bundled binary is looked up - and where the installer writes. */
	Path bundledDirectory() {
		return bundledDirectory;
	}

	public String ffmpeg() {
		return resolve(SettingsConstants.TOOL_FFMPEG, Tools::ffmpeg, "ffmpeg");
	}

	public String ffprobe() {
		return resolve(SettingsConstants.TOOL_FFPROBE, Tools::ffprobe, "ffprobe");
	}

	private String resolve(String settingKey, Function<Tools, String> configured, String executable) {
		String fallback = properties.tools() == null ? null : configured.apply(properties.tools());

		String stored = appSettingService.stringValue(settingKey, fallback);

		return stored == null || stored.isBlank() ? discover(executable) : stored;
	}

	/**
	 * The bundled binary when it exists, otherwise the bare command name. Both
	 * names are tried instead of branching on the operating system: what matters is
	 * which file is actually there. The extensionless name comes first so that a
	 * {@code .exe} left behind by a Windows download does not beat the real binary
	 * on Linux - on Windows nothing answers to the bare name, so the second
	 * candidate is the one that matches.
	 */
	private String discover(String executable) {
		for (String candidate : List.of(executable, executable + ".exe")) {
			Path bundled = bundledDirectory.resolve(candidate);

			if (Files.isRegularFile(bundled)) {
				return bundled.toString();
			}
		}

		return executable;
	}
}