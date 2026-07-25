package br.com.jorgemelo.nimbusfilemanager.security.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;

import br.com.jorgemelo.nimbusfilemanager.security.application.constants.AccessMessages;
import br.com.jorgemelo.nimbusfilemanager.security.application.constants.SecurityConstants;

class LoginFailureHandlerTest {

	@Test
	void onAuthenticationFailureShouldRedirectToDisabledWhenAccountIsNotConfirmedYet() throws Exception {
		UserAccessLogService userAccessLogService = mock(UserAccessLogService.class);

		AccountLockService accountLockService = mock(AccountLockService.class);

		LoginFailureHandler handler = new LoginFailureHandler(userAccessLogService, accountLockService);

		MockHttpServletRequest request = new MockHttpServletRequest();

		MockHttpServletResponse response = new MockHttpServletResponse();

		request.setParameter("username", "pending@example.com");

		handler.onAuthenticationFailure(request, response, new DisabledException("Account is disabled"));

		Assertions.assertThat(response.getRedirectedUrl()).isEqualTo("/login?disabled");

		verify(userAccessLogService).recordAccess(ArgumentMatchers.eq("pending@example.com"),
				ArgumentMatchers.eq(SecurityConstants.LOGIN_FAILURE), ArgumentMatchers.eq("FAILURE"),
				ArgumentMatchers.eq("127.0.0.1"), ArgumentMatchers.isNull(), ArgumentMatchers.anyString());
		verify(accountLockService, never()).registerFailure(ArgumentMatchers.any(), ArgumentMatchers.any(),
				ArgumentMatchers.any());
	}

	@Test
	void onAuthenticationFailureShouldRedirectToGenericErrorForOtherFailures() throws Exception {
		UserAccessLogService userAccessLogService = mock(UserAccessLogService.class);

		AccountLockService accountLockService = mock(AccountLockService.class);

		LoginFailureHandler handler = new LoginFailureHandler(userAccessLogService, accountLockService);

		MockHttpServletRequest request = new MockHttpServletRequest();

		MockHttpServletResponse response = new MockHttpServletResponse();

		request.setParameter("username", "user@example.com");
		request.addHeader("X-Forwarded-For", "203.0.113.7");
		request.addHeader("User-Agent", "JUnit");

		handler.onAuthenticationFailure(request, response, new BadCredentialsException("Bad credentials"));

		Assertions.assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");

		// The web boundary extracts IP/user-agent from the request and forwards the
		// primitives, so the services never see the servlet request.
		verify(userAccessLogService).recordAccess("user@example.com", SecurityConstants.LOGIN_FAILURE, "FAILURE",
				"203.0.113.7", "JUnit", AccessMessages.INVALID_CREDENTIALS);
		verify(accountLockService).registerFailure("user@example.com", "203.0.113.7", "JUnit");
	}

	/**
	 * Spring Security throws {@link LockedException} from
	 * {@code DaoAuthenticationProvider}'s pre-authentication checks before ever
	 * comparing the password (see {@code AppUserDetailsService}), so this must not
	 * be double-counted as a fresh failure -
	 * {@link AccountLockService#registerFailure} is a no-op while already locked
	 * anyway, but the handler shouldn't even call it here.
	 */
	@Test
	void onAuthenticationFailureShouldRedirectToLockedWithoutCountingAnotherFailure() throws Exception {
		UserAccessLogService userAccessLogService = mock(UserAccessLogService.class);

		AccountLockService accountLockService = mock(AccountLockService.class);

		LoginFailureHandler handler = new LoginFailureHandler(userAccessLogService, accountLockService);

		MockHttpServletRequest request = new MockHttpServletRequest();

		MockHttpServletResponse response = new MockHttpServletResponse();

		request.setParameter("username", "locked@example.com");

		handler.onAuthenticationFailure(request, response, new LockedException("Account is locked"));

		Assertions.assertThat(response.getRedirectedUrl()).isEqualTo("/login?locked");

		verify(userAccessLogService).recordAccess(ArgumentMatchers.eq("locked@example.com"),
				ArgumentMatchers.eq(SecurityConstants.LOGIN_FAILURE), ArgumentMatchers.eq("FAILURE"),
				ArgumentMatchers.eq("127.0.0.1"), ArgumentMatchers.isNull(), ArgumentMatchers.anyString());
		verify(accountLockService, never()).registerFailure(ArgumentMatchers.any(), ArgumentMatchers.any(),
				ArgumentMatchers.any());
	}
}