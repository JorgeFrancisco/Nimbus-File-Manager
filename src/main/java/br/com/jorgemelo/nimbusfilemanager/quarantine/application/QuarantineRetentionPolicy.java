package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;

/**
 * How long a quarantined file is kept, and whether anything has outstayed it.
 *
 * <p>
 * Separate from the purge that does the expunging because the two are asked in
 * different places: the window is read by the screen that promises it and by
 * the schedule that decides when to ask for a pass, while the purge itself runs
 * in the worker and is handed a window rather than reading one.
 */
@Service
public class QuarantineRetentionPolicy {

	/** Fail-safe: an unreadable retention window runs no purge at all. */
	private static final int DISABLED_RETENTION = -1;

	private final AppSettingService appSettingService;
	private final MovementRepository movementRepository;
	private final Clock clock;

	public QuarantineRetentionPolicy(AppSettingService appSettingService, MovementRepository movementRepository,
			Clock clock) {
		this.appSettingService = appSettingService;
		this.movementRepository = movementRepository;
		this.clock = clock;
	}

	/**
	 * How long a file stays in quarantine before the scheduled purge expunges it,
	 * or {@code 0} when no purge runs at all. The fallback is deliberately
	 * non-positive instead of the product default: a blank or invalid setting must
	 * disable the destructive purge, and must not promise the user a deadline
	 * nobody enforces. Read fresh on every call, so a change in Settings applies
	 * immediately.
	 */
	public int retentionDays() {
		return Math.max(0, appSettingService.intValue(SettingsConstants.TRASH_RETENTION_DAYS, DISABLED_RETENTION));
	}

	/**
	 * Whether anything is overdue right now. Asked before the daily pass is
	 * queued: a day with nothing to expunge writes no execution at all, because a
	 * daily row saying "0 purged" would bury the rows that record a real deletion.
	 * What is actually expunged is still decided when the purge runs.
	 */
	public boolean hasOverdue(int days) {
		if (days <= 0) {
			return false;
		}

		return movementRepository.existsByStatusAndReasonInAndMovedAtBefore(MovementStatus.MOVED,
				QuarantineConstants.QUARANTINED_REASONS, LocalDateTime.now(clock).minusDays(days));
	}
}