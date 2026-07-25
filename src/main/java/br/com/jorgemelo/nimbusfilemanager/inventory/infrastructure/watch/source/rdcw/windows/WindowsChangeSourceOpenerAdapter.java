package br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.rdcw.windows;

import java.nio.file.Path;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.FileChangeSource;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.WindowsChangeSourceOpener;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn.UsnCursorStore;

/**
 * Production adapter binding the {@link WindowsChangeSourceOpener} port to the
 * native {@link WindowsChangeSourceSupport#open} entry point, so the application
 * layer's provider never imports this infrastructure glue directly. Lives with the
 * native code (excluded from coverage) and only touches native code on Windows,
 * when the provider actually calls {@code open}.
 */
@Component
class WindowsChangeSourceOpenerAdapter implements WindowsChangeSourceOpener {

	@Override
	public FileChangeSource open(Path root, UsnCursorStore cursorStore, int bufferBytes) {
		return WindowsChangeSourceSupport.open(root, cursorStore, bufferBytes);
	}
}