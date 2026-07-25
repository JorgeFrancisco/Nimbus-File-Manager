package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn.UsnCursorStore;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.UsnJournalProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * Provides the Windows change source: {@code ReadDirectoryChangesW} for
 * recursive real-time events (single handle, no elevation, no folder lock),
 * plus a one-shot USN journal catch-up when the volume can be opened. Off
 * Windows, or when disabled, it returns empty <em>before</em> touching any JNA
 * class, so the native library never loads on Linux.
 *
 * <p>
 * It only returns empty (letting the factory fall back to the per-directory
 * {@code WatchService}) when even the single-handle recursive watch cannot be
 * opened - a missing USN privilege never causes that fallback, it just skips
 * the catch-up. The platform check and native open are injected seams so
 * selection and fallback are unit-tested on any OS.
 */
@Slf4j
@Component
public class WindowsFileChangeSourceProvider implements FileChangeSourceProvider {

	private final UsnJournalProperties properties;
	private final UsnCursorStore cursorStore;
	private final BooleanSupplier windows;
	private final WindowsChangeSourceOpener opener;

	@Autowired
	public WindowsFileChangeSourceProvider(UsnJournalProperties properties, UsnCursorStore cursorStore,
			WindowsChangeSourceOpener opener) {
		this(properties, cursorStore, WindowsFileChangeSourceProvider::isWindowsOs, opener);
	}

	WindowsFileChangeSourceProvider(UsnJournalProperties properties, UsnCursorStore cursorStore,
			BooleanSupplier windows,
			WindowsChangeSourceOpener opener) {
		this.properties = properties;
		this.cursorStore = cursorStore;
		this.windows = windows;
		this.opener = opener;
	}

	@Override
	public Optional<FileChangeSource> open(Path root) {
		if (!properties.enabled() || !windows.getAsBoolean()) {
			return Optional.empty();
		}

		try {
			FileChangeSource source = opener.open(root, cursorStore, properties.readBufferBytes());

			return Optional.of(source);
		} catch (RuntimeException | LinkageError failure) {
			// Even the recursive ReadDirectoryChangesW watch could not be opened (or JNA
			// could not load): fall back to the per-directory WatchService.
			log.warn("Recursive Windows watch unavailable for {}; falling back to WatchService ({})", root,
					failure.toString());

			return Optional.empty();
		}
	}

	static boolean isWindowsOs() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}
}