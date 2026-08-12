package br.com.jorgemelo.nimbusfilemanager.shared.application;

import java.nio.file.Files;
import java.nio.file.Path;

import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PreparedMovement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementProgress;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;

/**
 * Reads an operation on record against the disk and says how far it got.
 *
 * <p>
 * Every capability that moves a user's file writes the operation down before
 * touching anything, precisely so that an attempt which dies halfway leaves
 * something behind that says what it was in the middle of. What that record
 * means, though, is the same question everywhere: a pending operation whose
 * source is gone and whose destination is there was carried out and never
 * recorded, and repeating it would move a second file - or, worse, record a
 * second history for the first.
 *
 * <p>
 * Only that reading is shared. What counts as evidence that the thing at the
 * destination is <em>this</em> file differs by capability and deliberately so -
 * an organization move compares the size it already has, a restore compares the
 * digest it paid to read - so the decision to adopt stays with the caller, and
 * this says only which decision it is facing.
 */
public final class MovementRecovery {

	/**
	 * @param source where the operation said the file would come from
	 * @param target where it said the file would go
	 * @return which of the four situations the operation and the disk describe
	 * together; {@link MovementProgress#REFUSE} for an operation that is not
	 * there at all, which is a caller asking about work nobody reserved
	 */
	public static MovementProgress progressOf(PreparedMovement operation, Path source, Path target) {
		if (operation == null) {
			return MovementProgress.REFUSE;
		}

		if (operation.status() != MovementStatus.PENDING) {
			// Settled as anything else - skipped, failed, undone - is a decision somebody
			// already made about this file, and adopting it silently would overrule it.
			return operation.status() == MovementStatus.MOVED ? MovementProgress.ALREADY_DONE
					: MovementProgress.REFUSE;
		}

		if (Files.exists(source)) {
			// The file is still where it started, so nothing of this operation has
			// happened yet - whatever else may be true of the destination, which is the
			// caller's own rules to apply.
			return MovementProgress.EXECUTE;
		}

		return Files.exists(target) ? MovementProgress.RESUME : MovementProgress.REFUSE;
	}

	private MovementRecovery() {
	}
}