package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.PhysicalTreeWatcher;

/**
 * Chooses the change source for a monitored root: the platform provider's
 * source when available (the NTFS USN journal on Windows), otherwise the
 * portable {@code WatchService} source ({@link PhysicalTreeWatcher}). The
 * fallback is what keeps Linux - and any Windows box where the journal cannot
 * be opened - working exactly as before.
 */
@Component
public class FileChangeSourceFactory {

	private final FileChangeSourceProvider provider;

	public FileChangeSourceFactory(FileChangeSourceProvider provider) {
		this.provider = provider;
	}

	/**
	 * @param recursive whether the {@code WatchService} fallback registers the
	 *                  whole subtree; ignored by the USN source, which is
	 *                  inherently recursive without per-directory handles.
	 */
	public FileChangeSource create(Path root, boolean recursive) throws IOException {
		Optional<FileChangeSource> provided = provider.open(root);

		if (provided.isPresent()) {
			return provided.get();
		}

		return new PhysicalTreeWatcher(root, recursive);
	}
}