package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source;

import java.nio.file.Path;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn.UsnCursorStore;

/**
 * Seam over the Windows-only native open
 * ({@code WindowsChangeSourceSupport.open} - ReadDirectoryChangesW plus
 * optional USN catch-up), so the provider's selection and fallback branches are
 * driven by a fake on any platform. The production wiring points this at the
 * real native support.
 */
@FunctionalInterface
public interface WindowsChangeSourceOpener {

	FileChangeSource open(Path root, UsnCursorStore cursorStore, int bufferBytes);
}