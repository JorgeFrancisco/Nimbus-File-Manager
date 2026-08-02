package br.com.jorgemelo.nimbusfilemanager.settings.application;

import static br.com.jorgemelo.nimbusfilemanager.shared.application.constants.ToolFolders.FFMPEG;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.application.CoverageGenerated;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ToolsLocation;
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
 * Three sources, in order: the value edited on the Configurações screen -
 * skipped when it names a file that is no longer there - then the application
 * properties, which is how a container points at {@code /usr/bin}, and finally
 * discovery. Discovery exists because the properties used to ship a Windows
 * path ({@code ./tools/ffmpeg/bin/ffmpeg.exe}), so a clone on Linux or macOS
 * failed until someone found the setting to change, while the Docker image
 * quietly overrode it. The default now names no platform: the bundled binary is
 * used when it is there, and otherwise the bare command, which the operating
 * system resolves through PATH.
 */
@Component
public class ExternalToolPaths {

	private final AppSettingService appSettingService;
	private final NimbusFileManagerProperties properties;
	private final Path toolsDirectory;

	@Autowired
	@CoverageGenerated("Spring wiring: forwards to the constructor every test builds directly")
	public ExternalToolPaths(AppSettingService appSettingService, NimbusFileManagerProperties properties) {
		this(appSettingService, properties, ToolsLocation.of(FFMPEG));
	}

	/**
	 * Takes the binary directory so a test can point discovery at a folder it
	 * controls; production always uses the one under the workspace.
	 */
	ExternalToolPaths(AppSettingService appSettingService, NimbusFileManagerProperties properties,
			Path toolsDirectory) {
		this.appSettingService = appSettingService;
		this.properties = properties;
		this.toolsDirectory = toolsDirectory;
	}

	/** Where an installed binary is looked up - and where the installer writes. */
	Path toolsDirectory() {
		return toolsDirectory;
	}

	public String ffmpeg() {
		return resolve(SettingsConstants.TOOL_FFMPEG, Tools::ffmpeg, "ffmpeg");
	}

	public String ffprobe() {
		return resolve(SettingsConstants.TOOL_FFPROBE, Tools::ffprobe, "ffprobe");
	}

	private String resolve(String settingKey, Function<Tools, String> configured, String executable) {
		String saved = appSettingService.stringValue(settingKey, null);

		if (stillThere(saved)) {
			return saved;
		}

		String fallback = properties.tools() == null ? null : configured.apply(properties.tools());

		return fallback == null || fallback.isBlank() ? discover(executable) : fallback;
	}

	/**
	 * Whether a value saved on the settings screen still describes something
	 * runnable. A bare command name always does - PATH answers for it - but a value
	 * carrying a separator names one file and one only, and counts for nothing once
	 * that file is gone.
	 *
	 * <p>
	 * Only the saved value is checked, never the configured one. This is the value
	 * that lives in the catalog, so it travels inside a backup and lands on a
	 * machine whose tools are somewhere else entirely, and it outlives any rename
	 * of the folder they are installed into. A restore did both at once: every
	 * ffprobe call failed against {@code ./tools/bin/ffprobe.exe} - a folder from
	 * an older layout, on the installation the backup came from - while the right
	 * binary sat discovered and unused. The configured value has the opposite
	 * nature: it comes from the environment the application was started in, so it
	 * describes this machine by definition, and a container pointing at
	 * {@code /usr/bin} is taken at its word.
	 *
	 * <p>
	 * {@link File} rather than {@link Path}: {@code Path.of} throws on characters
	 * Windows forbids in a name, and a value typed by hand has to answer "not
	 * there" instead of ending the call. Only {@code null} is guarded against - a
	 * field cleared on the screen arrives here as nothing at all, because
	 * {@link AppSettingService#stringValue} discards a blank before returning.
	 */
	private static boolean stillThere(String value) {
		if (value == null) {
			return false;
		}

		boolean namesAFile = value.contains("/") || value.contains("\\");

		return !namesAFile || new File(value).isFile();
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
			Path installed = toolsDirectory.resolve(candidate);

			if (Files.isRegularFile(installed)) {
				return installed.toString();
			}
		}

		return executable;
	}
}