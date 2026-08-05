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

	/**
	 * The shape the queued restore and purge payloads are written in, and the only
	 * one a worker will run. Read by whoever queues and checked by whoever claims -
	 * the pair only means anything if both sides name the same number.
	 */
	public static final int PAYLOAD_SCHEMA_VERSION = 2;

	public static final String PAGE_KEY = "quarantine";

	/**
	 * How many items a single pass of the purge or of the absent-record cleanup
	 * will attempt, bounding memory and IO per run. Leftovers are simply taken by
	 * the next pass.
	 *
	 * <p>
	 * Read by both the pass that does the work and the check that decides whether
	 * there is work to queue, which is why it is here rather than inside one of
	 * them: the two would silently disagree about how much a run is.
	 */
	public static final int MAX_PER_RUN = 5_000;

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