package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Supplies a platform-specific {@link FileChangeSource} for a monitored root,
 * or empty when this provider does not apply (wrong OS, disabled, or the native
 * source could not be opened). The factory falls back to the portable
 * {@code WatchService} source when every provider returns empty. Kept as a seam
 * so source selection and fallback are tested with fakes on any platform.
 */
public interface FileChangeSourceProvider {

	/** @return a source for {@code root}, or empty to let the factory fall back. */
	Optional<FileChangeSource> open(Path root);
}