package br.com.jorgemelo.nimbusfilemanager.settings.application;

import static br.com.jorgemelo.nimbusfilemanager.shared.application.constants.ToolFolders.FFMPEG;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.application.CoverageGenerated;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ToolsLocation;

/**
 * Where the external tools live, resolved the same way for every caller. Every
 * feature that spawns ffmpeg or ffprobe (metadata extraction, perceptual
 * hashing, video conversion, thumbnails) reads the path from here, so a tool
 * that moves is a single change and no service carries settings plumbing just
 * to find a binary.
 *
 * <p>
 * The copy this application downloaded answers first, and the bare command name
 * - whatever PATH resolves - is only the fallback. A binary already on PATH is
 * of unknown provenance and may be old enough to lack the codecs this
 * application asks for, so it is what keeps the features working until the
 * download succeeds, never the preferred answer.
 *
 * <p>
 * Neither one can be overridden by a saved path, because that value used to
 * live in the catalog and therefore travelled inside a backup: a restore landed
 * another machine's path here and every ffprobe call failed against
 * {@code ./tools/bin/ffprobe.exe} for seventeen hours, while the right binary
 * sat discovered and unused. A location the application owns cannot describe
 * somebody else's machine.
 */
@Component
public class ExternalToolPaths {

	private final Path toolsDirectory;

	@Autowired
	@CoverageGenerated("Spring wiring: forwards to the constructor every test builds directly")
	public ExternalToolPaths() {
		this(ToolsLocation.of(FFMPEG));
	}

	/**
	 * Takes the binary directory so a test can point resolution at a folder it
	 * controls; production always uses the one under the workspace.
	 */
	ExternalToolPaths(Path toolsDirectory) {
		this.toolsDirectory = toolsDirectory;
	}

	/** Where an installed binary is looked up - and where the installer writes. */
	Path toolsDirectory() {
		return toolsDirectory;
	}

	public String ffmpeg() {
		return resolve("ffmpeg");
	}

	public String ffprobe() {
		return resolve("ffprobe");
	}

	/**
	 * Both names are tried instead of branching on the operating system: what
	 * matters is which file is actually there. The extensionless name comes first
	 * so that a {@code .exe} left behind by a Windows download does not beat the
	 * real binary on Linux - on Windows nothing answers to the bare name, so the
	 * second candidate is the one that matches.
	 */
	private String resolve(String executable) {
		for (String candidate : List.of(executable, executable + ".exe")) {
			Path installed = toolsDirectory.resolve(candidate);

			if (Files.isRegularFile(installed)) {
				return installed.toString();
			}
		}

		return executable;
	}
}