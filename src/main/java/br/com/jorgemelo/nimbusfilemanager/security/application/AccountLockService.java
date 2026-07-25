package br.com.jorgemelo.nimbusfilemanager.security.application;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.security.application.constants.AccessMessages;
import br.com.jorgemelo.nimbusfilemanager.security.application.constants.SecurityConstants;
import br.com.jorgemelo.nimbusfilemanager.security.domain.model.AppUser;
import br.com.jorgemelo.nimbusfilemanager.security.domain.repository.AppUserRepository;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;

/**
 * Shared brute-force guard for every surface that verifies a secret (password
 * or TOTP code) against an {@link AppUser}: form login
 * ({@code AppUserDetailsService} / {@code LoginFailureHandler}),
 * {@code /login/2fa} ({@code AuthWebController}) and {@code /change-password}
 * ({@code AppUserAccountService}) - see {@code SecurityConfigTest}.
 * <p>
 * Attempts are counted per account (not per surface), so an attacker who
 * already knows the password can't sidestep the lock by moving from
 * {@code /login/2fa} to {@code /change-password}, and vice-versa. The threshold
 * and lock duration are admin-configurable
 * ({@link AppSettingService#MAX_FAILED_LOGIN_ATTEMPTS},
 * {@link AppSettingService#LOCKOUT_DURATION_MINUTES}).
 */
@Service
public class AccountLockService {

	private final AppUserRepository appUserRepository;
	private final AppSettingService appSettingService;
	private final UserAccessLogService userAccessLogService;
	private final Clock clock;

	public AccountLockService(AppUserRepository appUserRepository, AppSettingService appSettingService,
			UserAccessLogService userAccessLogService, Clock clock) {
		this.appUserRepository = appUserRepository;
		this.appSettingService = appSettingService;
		this.userAccessLogService = userAccessLogService;
		this.clock = clock;
	}

	/**
	 * Registers one failed attempt. Once the configured threshold is reached, locks
	 * the account for the configured duration and resets the counter (rather than
	 * incrementing it forever), and records an
	 * {@link UserAccessLogService#ACCOUNT_LOCKED} entry. Does nothing while the
	 * account is already locked, so repeatedly hammering a locked account doesn't
	 * keep extending the lock. {@code ipAddress}/{@code userAgent} may be
	 * {@code null} (eg. {@code /change-password}, whose service entry point has no
	 * {@code HttpServletRequest} available) - the audit log simply omits IP and
	 * user-agent in that case.
	 */
	@Transactional
	public void registerFailure(String username, String ipAddress, String userAgent) {
		if (username == null || username.isBlank()) {
			return;
		}

		appUserRepository.findByUsernameIgnoreCase(username.trim()).ifPresent(user -> {
			if (user.isCurrentlyLocked()) {
				return;
			}

			LocalDateTime now = LocalDateTime.now(clock);

			int maxAttempts = maxAttempts();

			// Atomic +1 in the database: never loses a concurrent
			// attempt and is a no-op if the account got locked meanwhile.
			appUserRepository.incrementFailedAttempts(user.getId(), now);

			int lockoutMinutes = lockoutMinutes();

			LocalDateTime lockedUntil = now.plusMinutes(lockoutMinutes);

			// Applies (and logs) the lock exactly once - only the thread whose
			// increment crossed the threshold matches the >= maxAttempts guard.
			if (appUserRepository.applyLockoutIfThresholdReached(user.getId(), maxAttempts, lockedUntil, now) == 1) {
				userAccessLogService.recordAccess(user.getUsername(), SecurityConstants.ACCOUNT_LOCKED, "FAILURE",
						ipAddress, userAgent, AccessMessages.ACCOUNT_LOCKED);
			}
		});
	}

	/**
	 * Clears any accumulated failures/lock once the account proves the secret is
	 * known.
	 */
	@Transactional
	public void registerSuccess(String username) {
		if (username == null || username.isBlank()) {
			return;
		}

		// Atomic clear: only writes when there is something to clear,
		// so a success never races-away a concurrently-registered failure needlessly.
		appUserRepository.findByUsernameIgnoreCase(username.trim())
				.ifPresent(user -> appUserRepository.clearFailuresOnSuccess(user.getId(), LocalDateTime.now(clock)));
	}

	private int maxAttempts() {
		return Math.max(1, appSettingService.intValue(SettingsConstants.MAX_FAILED_LOGIN_ATTEMPTS, 5));
	}

	private int lockoutMinutes() {
		return Math.max(1, appSettingService.intValue(SettingsConstants.LOCKOUT_DURATION_MINUTES, 15));
	}
}