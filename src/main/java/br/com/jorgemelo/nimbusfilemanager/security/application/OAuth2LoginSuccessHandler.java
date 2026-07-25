package br.com.jorgemelo.nimbusfilemanager.security.application;

import java.io.IOException;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.security.application.constants.AccessMessages;
import br.com.jorgemelo.nimbusfilemanager.security.application.constants.SecurityConstants;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Google already authenticated the user out-of-band, so this handler doesn't
 * gate on {@link AccountLockService} the way {@code LoginFailureHandler} does
 * for password login - a lock caused by earlier failed local-password/2FA
 * attempts shouldn't block a legitimately Google-authenticated sign-in. It
 * still routes through {@code /login/2fa} when 2FA is enabled, so
 * {@code AuthWebController#verifyTwoFactor}'s lock check protects the TOTP code
 * from brute force regardless of whether the user arrived here via password or
 * Google.
 */
@Component
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

	private final AppUserAccountService appUserAccountService;
	private final AppUserDetailsService appUserDetailsService;
	private final UserAccessLogService userAccessLogService;
	private final AccountLockService accountLockService;

	public OAuth2LoginSuccessHandler(AppUserAccountService appUserAccountService,
			AppUserDetailsService appUserDetailsService, UserAccessLogService userAccessLogService,
			AccountLockService accountLockService) {
		this.appUserAccountService = appUserAccountService;
		this.appUserDetailsService = appUserDetailsService;
		this.userAccessLogService = userAccessLogService;
		this.accountLockService = accountLockService;

		setDefaultTargetUrl("/app");
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException, ServletException {
		if (!(authentication instanceof OAuth2AuthenticationToken oauth)) {
			super.onAuthenticationSuccess(request, response, authentication);

			return;
		}

		String email = oauth.getPrincipal().getAttribute("email");
		String name = oauth.getPrincipal().getAttribute("name");

		if (email == null || email.isBlank()) {
			throw new IllegalStateException("OAuth provider did not return an e-mail.");
		}

		var user = appUserAccountService.upsertOAuthUser(email, name);

		RequestClientInfo client = RequestClientInfo.from(request);

		if (Boolean.TRUE.equals(user.getTwoFactorEnabled())) {
			request.getSession(true).setAttribute(SecurityConstants.PENDING_USERNAME,
					user.getUsername());

			userAccessLogService.recordAccess(user.getUsername(), SecurityConstants.LOGIN_2FA_REQUIRED, "SUCCESS",
					client.ipAddress(), client.userAgent(), AccessMessages.TWO_FACTOR_REQUIRED_GOOGLE);

			SecurityContextHolder.clearContext();

			request.getSession().removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);

			getRedirectStrategy().sendRedirect(request, response, "/login/2fa");

			return;
		}

		var userDetails = appUserDetailsService.loadUserByUsername(user.getUsername());

		var localAuthentication = UsernamePasswordAuthenticationToken.authenticated(userDetails, null,
				userDetails.getAuthorities());

		var context = SecurityContextHolder.createEmptyContext();

		context.setAuthentication(localAuthentication);

		SecurityContextHolder.setContext(context);

		request.getSession().setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

		accountLockService.registerSuccess(userDetails.getUsername());

		userAccessLogService.recordAccess(userDetails.getUsername(), SecurityConstants.LOGIN_SUCCESS, "SUCCESS",
				client.ipAddress(), client.userAgent(), AccessMessages.GOOGLE_LOGIN_COMPLETED);

		super.onAuthenticationSuccess(request, response, localAuthentication);
	}
}