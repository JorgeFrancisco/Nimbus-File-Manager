package br.com.jorgemelo.nimbusfilemanager.settings.application;

import java.nio.file.Path;
import java.util.Optional;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * Where the quarantine folder may live relative to the monitored library.
 *
 * <p>
 * Two situations, two answers. A quarantine <b>inside</b> the library (or a
 * library inside the quarantine) is refused: everything under the quarantine
 * root is skipped by the inventory, the reconcile and the Arquivos screen, so
 * nesting one in the other silently hides part of the library from the catalog
 * and a file dropped there by mistake becomes invisible.
 *
 * <p>
 * A quarantine on the <b>same volume</b> is only warned about: it is a
 * defensible choice - the move stays within one file system, which is faster
 * and never crosses a device boundary - but a soft delete then frees no space
 * until the purge, and on a volume mirrored by a sync client it uploads what
 * the user meant to get rid of. The user decides; the screen has to say it.
 */
@Service
public class QuarantineFolderPolicy extends LocalizedComponent {

	private final AppSettingService appSettingService;

	public QuarantineFolderPolicy(AppSettingService appSettingService) {
		this.appSettingService = appSettingService;
	}

	/** Refuses a quarantine folder that overlaps the monitored library. */
	public void validateQuarantineFolder(String folder) {
		validateOverlap(folder, configured(SettingsConstants.WATCH_FOLDER));
	}

	/** The same rule from the other side: a library and a quarantine that meet. */
	public void validateLibraryFolder(String folder) {
		validateOverlap(configured(SettingsConstants.TRASH_FOLDER), folder);
	}

	/**
	 * Localized warning about the quarantine currently configured, or empty when
	 * there is nothing to say. A configuration that predates this policy can still
	 * be nested, so that case is reported too instead of being reduced to the
	 * milder same-volume text.
	 */
	public Optional<String> warning() {
		Path quarantine = normalized(configured(SettingsConstants.TRASH_FOLDER));
		Path library = normalized(configured(SettingsConstants.WATCH_FOLDER));

		if (quarantine == null || library == null) {
			return Optional.empty();
		}

		if (quarantine.startsWith(library)) {
			return Optional.of(message("backend.settings.quarantineInsideLibrary"));
		}

		if (library.startsWith(quarantine)) {
			return Optional.of(message("backend.settings.libraryInsideQuarantine"));
		}

		return sameVolume(quarantine, library)
				? Optional.of(message("backend.settings.quarantineSameVolume", volumeOf(quarantine)))
				: Optional.empty();
	}

	private void validateOverlap(String quarantineFolder, String libraryFolder) {
		Path quarantine = normalized(quarantineFolder);
		Path library = normalized(libraryFolder);

		if (quarantine == null || library == null) {
			return;
		}

		// startsWith also covers the two being the same folder, which is the worst
		// case of all: the whole library would be excluded from every scan.
		if (quarantine.startsWith(library)) {
			throw new IllegalArgumentException(message("backend.settings.quarantineInsideLibrary"));
		}

		if (library.startsWith(quarantine)) {
			throw new IllegalArgumentException(message("backend.settings.libraryInsideQuarantine"));
		}
	}

	private String configured(String key) {
		return appSettingService.stringValue(key, "");
	}

	private static Path normalized(String folder) {
		return folder == null || folder.isBlank() ? null : Path.of(folder.trim()).toAbsolutePath().normalize();
	}

	// Both paths went through toAbsolutePath, and an absolute path always has a
	// root, so there is nothing to guard against here.
	private static boolean sameVolume(Path first, Path second) {
		return first.getRoot().equals(second.getRoot());
	}

	private static String volumeOf(Path path) {
		return path.getRoot().toString();
	}
}