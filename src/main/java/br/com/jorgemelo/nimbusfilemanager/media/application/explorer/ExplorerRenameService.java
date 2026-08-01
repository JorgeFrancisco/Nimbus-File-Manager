package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.ExplorerActionResult;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureFileMove;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.ExtensionUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * Renaming from the file explorer. The rename is a move like any other, so it
 * goes through {@link SecureFileMove} (hash baseline, byte-for-byte verify,
 * roll-back) rather than {@code Files.move}: it is the user's own media, and
 * the project reserves the plain move for regenerable artefacts.
 *
 * <p>
 * The catalog row is rewritten in the same transaction. Leaving it to the
 * background reconciliation would also heal it, but only after the next pass -
 * until then the screen would show the old name and a file marked missing.
 */
@Slf4j
@Service
public class ExplorerRenameService extends LocalizedComponent {

	/** Same reason as the deletion service: a click should not lose to a scan. */
	private static final Duration LOCK_WAIT = Duration.ofSeconds(20);

	/** Characters Windows forbids in a name, plus the path separators. */
	private static final Pattern INVALID_NAME = Pattern.compile("[\\\\/:*?\"<>|]");

	private final ExplorerDeletionGuard guard;
	private final SecureFileMove secureFileMove;
	private final CatalogFileRepository catalogFileRepository;
	private final OperationLockService operationLockService;

	public ExplorerRenameService(ExplorerDeletionGuard guard, SecureFileMove secureFileMove,
			CatalogFileRepository catalogFileRepository, OperationLockService operationLockService) {
		this.guard = guard;
		this.secureFileMove = secureFileMove;
		this.catalogFileRepository = catalogFileRepository;
		this.operationLockService = operationLockService;
	}

	@Transactional
	public ExplorerActionResult rename(Path path, String newName) {
		Path source = PathUtils.normalizePath(path.toString());

		Optional<String> refusal = guard.refusal(source);

		if (refusal.isPresent()) {
			return ExplorerActionResult.refused(refusal.get());
		}

		String trimmed = newName == null ? "" : newName.trim();

		if (trimmed.isBlank() || INVALID_NAME.matcher(trimmed).find()) {
			return ExplorerActionResult.refused(message("backend.files.renameInvalidName"));
		}

		Path parent = source.getParent();

		if (parent == null) {
			return ExplorerActionResult.refused(message("backend.files.renameInvalidName"));
		}

		Path target = parent.resolve(trimmed).normalize();

		if (Files.exists(target)) {
			return ExplorerActionResult.refused(message("backend.files.renameTargetExists", trimmed));
		}

		try (var _ = operationLockService.acquireWithin(LOCK_WAIT, ExecutionType.ORGANIZATION, source)) {
			return renameLocked(source, target, trimmed);
		} catch (OperationLockException e) {
			log.warn("Explorer rename blocked because another operation is using {}: {}", source, e.getMessage());

			return ExplorerActionResult.refused(message("backend.files.busy"));
		}
	}

	private ExplorerActionResult renameLocked(Path source, Path target, String newName) {
		boolean directory = Files.isDirectory(source);

		try {
			if (directory) {
				// A folder has no bytes of its own to verify; the secure move exists to protect
				// file content, so the rename of the container itself is a plain move.
				Files.move(source, target);
			} else {
				secureFileMove.move(source, target, false);
			}
		} catch (IOException e) {
			log.error("Explorer could not rename {} to {}", source, target, e);

			return ExplorerActionResult.refused(message("backend.files.renameFailed", e.getMessage()));
		}

		if (!directory) {
			rewriteCatalog(source, target, newName);
		}

		return new ExplorerActionResult(true, message("backend.files.renameDone", newName), 1, 0, 0);
	}

	/**
	 * Points the catalog entry at the new path. A folder rename moves every file
	 * under it, which the reconciliation repairs on its next pass - rewriting a
	 * whole subtree here would duplicate that logic for the rarer case.
	 */
	private void rewriteCatalog(Path source, Path target, String newName) {
		Optional<CatalogFile> stored = catalogFileRepository.findByFileKey(PathUtils.normalize(source));

		if (stored.isEmpty()) {
			return;
		}

		CatalogFile file = stored.get();

		file.setFileKey(PathUtils.normalize(target));
		file.setFileName(newName);
		file.setExtension(ExtensionUtils.fromPath(target));

		catalogFileRepository.save(file);
	}
}