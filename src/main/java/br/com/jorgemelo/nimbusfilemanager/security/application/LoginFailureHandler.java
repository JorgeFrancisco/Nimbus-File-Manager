package br.com.jorgemelo.nimbusfilemanager.security.application;

import java.io.IOException;

import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.security.application.constants.AccessMessages;
import br.com.jorgemelo.nimbusfilemanager.security.application.constants.SecurityConstants;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Distinguishes "this account exists but hasn't confirmed its email yet" and
 * "this account is temporarily locked out" from every other login failure
 * (wrong password, unknown user, ...), instead of showing a generic "invalid
 * credentials" message for all of them.
 * <p>
 * Spring Security's {@code DaoAuthenticationProvider} runs
 * {@code preAuthenticationChecks} - which throws {@link DisabledException} when
 * {@code AppUser.enabled} is false, and {@link LockedException} when
 * {@code AppUserDetailsService} reports the account as
 * {@code accountNonLocked=false} (see {@code AppUser#isCurrentlyLocked}) -
 * <i>before</i> ever comparing the password. So both surface here regardless of
 * whether the submitted password was even correct, and neither should count as
 * a fresh failed attempt via {@link AccountLockService} (the disabled check has
 * nothing to do with brute-forcing, and the locked account is already locked).
 */
@Component
public class LoginFailureHandler implements AuthenticationFailureHandler {

	private final UserAccessLogService userAccessLogService;
	private final AccountLockService accountLockService;

	public LoginFailureHandler(UserAccessLogService userAccessLogService, AccountLockService accountLockService) {
		this.userAccessLogService = userAccessLogService;
		this.accountLockService = accountLockService;
	}

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException, ServletException {
		String username = request.getParameter("username");

		RequestClientInfo client = RequestClientInfo.from(request);

		boolean disabled = exception instanceof DisabledException;
		boolean locked = exception instanceof LockedException;

		if (!disabled && !locked) {
			accountLockService.registerFailure(username, client.ipAddress(), client.userAgent());
		}

		String message;

		if (disabled) {
			message = AccessMessages.ACCOUNT_NOT_CONFIRMED;
		} else if (locked) {
			message = AccessMessages.ACCOUNT_TEMPORARILY_LOCKED;
		} else {
			message = AccessMessages.INVALID_CREDENTIALS;
		}

		userAccessLogService.recordAccess(username, SecurityConstants.LOGIN_FAILURE, "FAILURE", client.ipAddress(),
				client.userAgent(), message);

		String redirectSuffix;

		if (disabled) {
			redirectSuffix = "?disabled";
		} else if (locked) {
			redirectSuffix = "?locked";
		} else {
			redirectSuffix = "?error";
		}

		response.sendRedirect(request.getContextPath() + "/login" + redirectSuffix);
	}
}