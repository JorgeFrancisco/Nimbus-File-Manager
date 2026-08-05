package br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.usn.windows;

import java.nio.file.Path;
import java.util.Optional;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.PersistedCursor;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn.UsnWatermarkOpener;

/**
 * Production adapter binding the {@link UsnWatermarkOpener} port to the native
 * entry point, so the application layer never imports this glue directly. Lives
 * with the native code and answers empty everywhere the volume cannot be
 * opened, which is every platform but Windows and every run without elevation.
 */
@Component
class WindowsUsnWatermarkAdapter implements UsnWatermarkOpener {

	@Override
	public Optional<PersistedCursor> read(Path root) {
		return WindowsUsnSupport.readWatermark(root);
	}
}