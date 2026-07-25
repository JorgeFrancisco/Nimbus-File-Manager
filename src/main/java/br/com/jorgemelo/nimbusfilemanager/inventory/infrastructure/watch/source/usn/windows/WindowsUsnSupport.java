package br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.usn.windows;

import java.nio.file.Path;
import java.util.Optional;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn.UsnCatchUpResult;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn.UsnCursorStore;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn.UsnUnavailableException;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn.WindowsUsnCatchUp;
import lombok.extern.slf4j.Slf4j;

/**
 * Windows-only entry point for the USN startup catch-up: opens the hosting NTFS
 * volume, runs the one-shot catch-up, and closes the volume again (the live
 * events come from {@code ReadDirectoryChangesW}, so the volume is not kept
 * open). Opening the volume needs the manage-volume/backup privilege, so this
 * is best-effort: on {@code ERROR_ACCESS_DENIED} (no elevation) it returns
 * empty and the caller keeps using {@code ReadDirectoryChangesW} alone.
 */
@Slf4j
public final class WindowsUsnSupport {

	private WindowsUsnSupport() {
	}

	/** @return the catch-up result, or empty when the journal cannot be opened. */
	public static Optional<UsnCatchUpResult> tryCatchUp(Path root, UsnCursorStore cursorStore, int bufferBytes) {
		Path normalized = root.toAbsolutePath().normalize();

		String driveLetter = driveLetterOf(normalized);

		if (driveLetter == null) {
			return Optional.empty();
		}

		try {
			FfmUsnVolume volume = FfmUsnVolume.open(driveLetter);

			try {
				FfmUsnPathResolver resolver = new FfmUsnPathResolver(volume.nativeHandle());

				return Optional.of(WindowsUsnCatchUp.catchUp(normalized, volume, resolver, cursorStore,
						normalized.toString(), bufferBytes));
			} finally {
				volume.close();
			}
		} catch (UsnUnavailableException unavailable) {
			log.info("USN catch-up unavailable for {} (using real-time watch only): {}", normalized,
					unavailable.getMessage());

			return Optional.empty();
		}
	}

	private static String driveLetterOf(Path path) {
		Path root = path.getRoot();

		if (root == null) {
			return null;
		}

		String text = root.toString();

		if (text.length() < 2 || text.charAt(1) != ':') {
			return null;
		}

		return String.valueOf(Character.toUpperCase(text.charAt(0)));
	}
}