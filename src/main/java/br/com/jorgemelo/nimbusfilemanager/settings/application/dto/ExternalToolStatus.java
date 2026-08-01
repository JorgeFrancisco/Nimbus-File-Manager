package br.com.jorgemelo.nimbusfilemanager.settings.application.dto;

/**
 * What the settings screen shows about ffmpeg/ffprobe. The screen renders these
 * fields and nothing else - whether the install action is offered is decided
 * here ({@code installable}), never by the template inspecting the operating
 * system.
 */
public record ExternalToolStatus(boolean ffmpegAvailable, String ffmpegPath, boolean ffprobeAvailable,
		String ffprobePath, String version, boolean bundled, boolean installable, String directory) {

	public boolean complete() {
		return ffmpegAvailable && ffprobeAvailable;
	}
}