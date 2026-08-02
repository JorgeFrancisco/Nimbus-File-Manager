package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.PhysicalTreeWatcher;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;

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
	private final SelfWrittenPathRegistry selfWrittenPathRegistry;
	private final ScanExclusionService scanExclusionService;

	public FileChangeSourceFactory(FileChangeSourceProvider provider,
			SelfWrittenPathRegistry selfWrittenPathRegistry, ScanExclusionService scanExclusionService) {
		this.provider = provider;
		this.selfWrittenPathRegistry = selfWrittenPathRegistry;
		this.scanExclusionService = scanExclusionService;
	}

	/**
	 * @param recursive whether the {@code WatchService} fallback registers the
	 * whole subtree; ignored by the USN source, which is inherently recursive
	 * without per-directory handles.
	 */
	public FileChangeSource create(Path root, boolean recursive) throws IOException {
		Optional<FileChangeSource> provided = provider.open(root);

		// Every source is wrapped, whichever one it is: the application's own writes
		// are uninteresting to the watcher regardless of how they were detected.
		if (provided.isPresent()) {
			return new SelfWriteAwareFileChangeSource(provided.get(), selfWrittenPathRegistry, scanExclusionService);
		}

		return new SelfWriteAwareFileChangeSource(new PhysicalTreeWatcher(root, recursive), selfWrittenPathRegistry,
			scanExclusionService);
	}
}