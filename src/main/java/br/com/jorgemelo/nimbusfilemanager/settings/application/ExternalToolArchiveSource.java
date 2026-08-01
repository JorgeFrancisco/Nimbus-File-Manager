package br.com.jorgemelo.nimbusfilemanager.settings.application;

import java.nio.file.Path;

/**
 * Where the ffmpeg/ffprobe archive comes from. A port because the concrete
 * source is an HTTP download of a third-party build: the installer is tested
 * against a local archive, and pointing at a mirror later changes one adapter.
 */
public interface ExternalToolArchiveSource {

	/**
	 * Downloads the archive into {@code targetFolder} and returns it. The caller
	 * owns the file and deletes it once extracted.
	 */
	Path download(Path targetFolder);
}