package br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants;

import java.util.Set;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;

/**
 * Contract data constants for the quarantine domain: the preferences page key
 * for the Quarentena screen, so the chosen view mode and page size are
 * remembered per user across visits, and the movement reasons that mean "this
 * file is currently in quarantine".
 */
public final class QuarantineConstants {

	public static final String PAGE_KEY = "quarantine";

	/**
	 * Every reason that puts a file into the quarantine folder. There is a single
	 * quarantine shared by all features that soft-delete a file - duplicate
	 * removal, the original kept behind by a video conversion, and a file the user
	 * sent there from the file explorer - so listing, restoring and purging always
	 * match on the whole set.
	 *
	 * <p>
	 * Which is why a reason missing from here is not a display bug. The set gates
	 * the restore and the purge as well, so a file whose reason is absent lands in
	 * the folder and then cannot be seen, brought back, or ever cleaned up:
	 * stranded between the screen that put it there and the one that owns it.
	 * {@link MovementReason#USER_QUARANTINED} spent a release exactly like that,
	 * with the enum value, the intake and both message bundles in place, and only
	 * this line missing.
	 */
	public static final Set<MovementReason> QUARANTINED_REASONS = Set.of(MovementReason.DUPLICATE_QUARANTINED,
			MovementReason.CONVERTED_QUARANTINED, MovementReason.USER_QUARANTINED);

	private QuarantineConstants() {
	}
}