package br.com.jorgemelo.nimbusfilemanager.security.application;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.security.application.constants.AccessMessages;
import br.com.jorgemelo.nimbusfilemanager.security.application.constants.SecurityConstants;
import br.com.jorgemelo.nimbusfilemanager.security.domain.repository.AppUserRepository;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Runs after the password has already been verified by
 * {@code DaoAuthenticationProvider}. When 2FA is enabled it deliberately does
 * <strong>not</strong> reset {@link AccountLockService}'s failure counter yet -
 * that only happens once {@code AuthWebController#verifyTwoFactor} accepts the
 * TOTP code too, so a correct password alone doesn't buy unlimited attempts at
 * the 2FA code.
 */
@Component
public class TwoFactorAuthenticationSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

	private final AppUserRepository appUserRepository;
	private final UserAccessLogService userAccessLogService;
	private final AccountLockService accountLockService;

	public TwoFactorAuthenticationSuccessHandler(AppUserRepository appUserRepository,
			UserAccessLogService userAccessLogService, AccountLockService accountLockService) {
		this.appUserRepository = appUserRepository;
		this.userAccessLogService = userAccessLogService;
		this.accountLockService = accountLockService;

		setDefaultTargetUrl("/app");
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException, ServletException {
		var user = appUserRepository.findByUsername(authentication.getName());

		RequestClientInfo client = RequestClientInfo.from(request);

		if (user.isPresent() && Boolean.TRUE.equals(user.get().getTwoFactorEnabled())) {
			request.getSession(true).setAttribute(SecurityConstants.PENDING_USERNAME, authentication.getName());

			userAccessLogService.recordAccess(authentication.getName(), SecurityConstants.LOGIN_2FA_REQUIRED,
					"SUCCESS", client.ipAddress(), client.userAgent(), AccessMessages.TWO_FACTOR_REQUIRED);

			SecurityContextHolder.clearContext();

			getRedirectStrategy().sendRedirect(request, response, "/login/2fa");

			return;
		}

		accountLockService.registerSuccess(authentication.getName());

		userAccessLogService.recordAccess(authentication.getName(), SecurityConstants.LOGIN_SUCCESS, "SUCCESS",
				client.ipAddress(), client.userAgent(), AccessMessages.LOGIN_COMPLETED);

		super.onAuthenticationSuccess(request, response, authentication);
	}
}