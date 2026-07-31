package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PhysicalFilePolicy;

/**
 * Decides whether the explorer may destroy or rename a path at all. The
 * explorer browses (and previews) anywhere on disk, which is fine for reading;
 * writing is a different matter, so every destructive action is confined to the
 * monitored library. Without that, a mistyped path in a request would let an
 * admin delete anything the process can reach.
 *
 * <p>
 * Returns the localized reason for a refusal instead of a boolean, because the
 * user has to be told why the action did not happen - a silent no-op reads as
 * success.
 */
@Component
public class ExplorerDeletionGuard extends LocalizedComponent {

	private final AppSettingService appSettingService;

	public ExplorerDeletionGuard(AppSettingService appSettingService) {
		this.appSettingService = appSettingService;
	}

	/**
	 * The reason {@code target} cannot be written to, or empty when it can.
	 */
	public Optional<String> refusal(Path target) {
		Optional<Path> library = library();

		if (library.isEmpty()) {
			return Optional.of(message("backend.files.libraryNotConfigured"));
		}

		if (!Files.exists(target)) {
			return Optional.of(message("backend.files.pathGone"));
		}

		if (!PhysicalFilePolicy.isProcessable(target)) {
			return Optional.of(message("backend.files.notPhysical"));
		}

		Path root = library.get();

		// Equal to the library root, not merely outside it: emptying the monitored
		// folder itself is never what a click on a card meant.
		if (target.equals(root) || !target.startsWith(root)) {
			return Optional.of(message("backend.files.outsideLibrary", PathUtils.normalize(root)));
		}

		return Optional.empty();
	}

	private Optional<Path> library() {
		String configured = appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "");

		return configured.isBlank() ? Optional.empty() : Optional.of(PathUtils.normalizePath(configured));
	}
}