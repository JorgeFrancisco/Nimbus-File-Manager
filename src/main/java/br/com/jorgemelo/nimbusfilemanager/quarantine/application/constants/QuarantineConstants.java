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
	 * quarantine shared by all features that soft-delete a file (duplicate removal
	 * and the original kept behind by a video conversion), so listing, restoring
	 * and purging always match on the whole set - a new intake reason only needs to
	 * be added here to be visible on the Quarentena screen.
	 */
	public static final Set<MovementReason> QUARANTINED_REASONS = Set.of(MovementReason.DUPLICATE_QUARANTINED,
			MovementReason.CONVERTED_QUARANTINED);

	private QuarantineConstants() {
	}
}