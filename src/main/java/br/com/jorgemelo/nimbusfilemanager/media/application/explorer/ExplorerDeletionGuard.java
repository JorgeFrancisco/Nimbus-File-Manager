package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.media.application.constants.ExplorerMessages;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
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
 * Returns the reason for a refusal instead of a boolean, because the user has
 * to be told why the action did not happen - a silent no-op reads as success.
 * As a code rather than as text, because the same question is now asked twice:
 * once by the application, before a command is queued, where the answer is
 * shown to whoever is looking; and once by the worker, before it acts, where
 * the answer is recorded on the execution and there is no language to write it
 * in. Time passes between the two, and a path can stop being writable in it.
 */
@Component
public class ExplorerDeletionGuard {

	private final AppSettingService appSettingService;

	public ExplorerDeletionGuard(AppSettingService appSettingService) {
		this.appSettingService = appSettingService;
	}

	/**
	 * The reason {@code target} cannot be written to, or empty when it can.
	 */
	public Optional<ExecutionMessage> refusal(Path target) {
		Optional<Path> library = library();

		if (library.isEmpty()) {
			return Optional.of(ExplorerMessages.libraryNotConfigured());
		}

		if (!Files.exists(target)) {
			return Optional.of(ExplorerMessages.pathGone());
		}

		if (!PhysicalFilePolicy.isProcessable(target)) {
			return Optional.of(ExplorerMessages.notPhysical());
		}

		Path root = library.get();

		// Equal to the library root, not merely outside it: emptying the monitored
		// folder itself is never what a click on a card meant.
		if (target.equals(root) || !target.startsWith(root)) {
			return Optional.of(ExplorerMessages.outsideLibrary(PathUtils.normalize(root)));
		}

		return Optional.empty();
	}

	private Optional<Path> library() {
		String configured = appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "");

		return configured.isBlank() ? Optional.empty() : Optional.of(PathUtils.normalizePath(configured));
	}
}