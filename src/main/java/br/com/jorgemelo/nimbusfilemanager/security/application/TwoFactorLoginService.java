package br.com.jorgemelo.nimbusfilemanager.security.application;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.security.application.constants.AccessMessages;
import br.com.jorgemelo.nimbusfilemanager.security.application.constants.SecurityConstants;
import br.com.jorgemelo.nimbusfilemanager.security.domain.model.AppUser;
import br.com.jorgemelo.nimbusfilemanager.security.domain.repository.AppUserRepository;

/**
 * Owns the 2FA-login business decision for {@code POST /login/2fa}: it checks
 * the account lock, verifies the TOTP code, updates the brute-force counters and
 * records the audit log, returning only which outcome the web layer must render.
 * The controller keeps solely the web-security infrastructure (session and
 * {@code SecurityContext}); no business decision lives there.
 */
@Service
public class TwoFactorLoginService {

	private final AppUserRepository appUserRepository;
	private final TwoFactorService twoFactorService;
	private final AccountLockService accountLockService;
	private final UserAccessLogService userAccessLogService;

	public TwoFactorLoginService(AppUserRepository appUserRepository, TwoFactorService twoFactorService,
			AccountLockService accountLockService, UserAccessLogService userAccessLogService) {
		this.appUserRepository = appUserRepository;
		this.twoFactorService = twoFactorService;
		this.accountLockService = accountLockService;
		this.userAccessLogService = userAccessLogService;
	}

	public TwoFactorLoginResult verify(String username, String code, String ipAddress, String userAgent) {
		AppUser user = appUserRepository.findByUsername(username).orElseThrow();

		if (user.isCurrentlyLocked()) {
			userAccessLogService.recordAccess(username, SecurityConstants.LOGIN_2FA_FAILURE, "FAILURE", ipAddress,
					userAgent, AccessMessages.TWO_FACTOR_REJECTED_LOCKED);

			return TwoFactorLoginResult.LOCKED;
		}

		if (!twoFactorService.verify(user.getTwoFactorSecret(), code)) {
			accountLockService.registerFailure(username, ipAddress, userAgent);

			userAccessLogService.recordAccess(username, SecurityConstants.LOGIN_2FA_FAILURE, "FAILURE", ipAddress,
					userAgent, AccessMessages.INVALID_TWO_FACTOR_CODE);

			return TwoFactorLoginResult.INVALID;
		}

		accountLockService.registerSuccess(username);

		userAccessLogService.recordAccess(username, SecurityConstants.LOGIN_2FA_SUCCESS, "SUCCESS", ipAddress,
				userAgent, AccessMessages.TWO_FACTOR_COMPLETED);

		return TwoFactorLoginResult.SUCCESS;
	}
}