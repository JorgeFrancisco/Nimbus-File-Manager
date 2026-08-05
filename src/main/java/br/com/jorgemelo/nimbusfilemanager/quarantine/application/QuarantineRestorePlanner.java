package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreOptions;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestorePlan;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.ConflictResolution;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.RestoreOutcome;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.FileNames;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PhysicalFilePolicy;

/**
 * The conversation about restoring one file, held where there is somebody to
 * answer it.
 *
 * <p>
 * A name collision and a missing origin folder are questions, not outcomes: the
 * screen shows a dialog and the person decides. That is why this reads and only
 * reads - it looks at the movement, at the quarantine copy and at the
 * destination, and it either hands back the question or the destination the
 * restore will use. Nothing here changes a file, and nothing here holds the
 * capability to.
 *
 * <p>
 * Which is exactly what lets the move itself go to the worker: by the time the
 * request is queued the intention is complete, so the worker has nothing left
 * to ask. What it may still find is that the world moved underneath - the
 * destination taken, the copy purged - and that becomes the execution's own
 * outcome, not a second question.
 */
@Service
public class QuarantineRestorePlanner extends LocalizedComponent {

	private final MovementRepository movementRepository;

	public QuarantineRestorePlanner(MovementRepository movementRepository) {
		this.movementRepository = movementRepository;
	}

	/**
	 * Reads the request against the world as it is now.
	 *
	 * @return the answer to show, or the file the restore must create
	 */
	@Transactional(readOnly = true)
	public QuarantineRestorePlan plan(UUID movementId, QuarantineRestoreOptions options) {
		QuarantineRestoreOptions effective = options == null ? QuarantineRestoreOptions.defaults() : options;

		Movement movement = movementRepository.findByPublicId(movementId).orElse(null);

		if (movement == null) {
			return answer(movementId, RestoreOutcome.ERROR, "backend.quarantine.itemNotFound");
		}

		if (movement.getStatus() != MovementStatus.MOVED
				|| !QuarantineConstants.QUARANTINED_REASONS.contains(movement.getReason())) {
			return answer(movementId, RestoreOutcome.ERROR, "backend.quarantine.notQuarantined");
		}

		if (effective.conflictResolution() == ConflictResolution.SKIP) {
			return answer(movementId, RestoreOutcome.SKIPPED, "backend.quarantine.restoreSkipped");
		}

		return planMove(movementId, movement, effective);
	}

	private QuarantineRestorePlan planMove(UUID movementId, Movement movement, QuarantineRestoreOptions options) {
		Path quarantine = PathUtils.normalizePath(movement.getTargetPath());

		if (!Files.exists(quarantine)) {
			return answer(movementId, RestoreOutcome.MISSING_IN_QUARANTINE, "backend.quarantine.fileMissing");
		}

		// Same rule as the forward path: never follow a symlink/junction/.lnk. If the
		// quarantine copy was swapped for a link, refuse instead of "restoring" the
		// link into the library.
		if (!PhysicalFilePolicy.isProcessable(quarantine)) {
			return answer(movementId, RestoreOutcome.ERROR, "backend.quarantine.notPhysical");
		}

		Path original = PathUtils.normalizePath(movement.getSourcePath());

		boolean usingOverride = options.destinationFolder() != null;

		Path destinationFolder = usingOverride ? PathUtils.normalizePath(options.destinationFolder().toString())
				: original.getParent();

		if (destinationFolder == null) {
			return answer(movementId, RestoreOutcome.ERROR, "backend.quarantine.invalidOriginalPath");
		}

		if (!usingOverride && !Files.isDirectory(destinationFolder)) {
			return answer(movementId, RestoreOutcome.ORIGIN_MISSING, "backend.quarantine.originMissing");
		}

		return planDestination(movementId, quarantine, destinationFolder.resolve(original.getFileName()), options);
	}

	private QuarantineRestorePlan planDestination(UUID movementId, Path quarantine, Path destination,
			QuarantineRestoreOptions options) {
		if (!Files.exists(destination)) {
			return QuarantineRestorePlan.move(quarantine, destination);
		}

		// Renaming is the person's answer to the collision, so the new name is chosen
		// here, with them still there - never by the worker, which would be deciding
		// on their behalf.
		if (options.conflictResolution() == ConflictResolution.RENAME) {
			return QuarantineRestorePlan.move(quarantine, FileNames.nextAvailable(destination));
		}

		return answer(movementId, RestoreOutcome.CONFLICT, "backend.quarantine.destinationConflict");
	}

	private QuarantineRestorePlan answer(UUID movementId, RestoreOutcome outcome, String code) {
		return QuarantineRestorePlan.answered(new QuarantineRestoreResult(false, outcome.name(), message(code),
				movementId, null));
	}
}