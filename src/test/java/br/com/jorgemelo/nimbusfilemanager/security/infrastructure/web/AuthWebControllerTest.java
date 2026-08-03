package br.com.jorgemelo.nimbusfilemanager.security.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import br.com.jorgemelo.nimbusfilemanager.security.application.AccountLockService;
import br.com.jorgemelo.nimbusfilemanager.security.application.AppUserAccountService;
import br.com.jorgemelo.nimbusfilemanager.security.application.AppUserDetailsService;
import br.com.jorgemelo.nimbusfilemanager.security.application.TwoFactorLoginResult;
import br.com.jorgemelo.nimbusfilemanager.security.application.TwoFactorLoginService;
import br.com.jorgemelo.nimbusfilemanager.security.application.constants.SecurityConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Security;
import jakarta.servlet.http.HttpSession;

class AuthWebControllerTest {

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void authShouldCompleteTwoFactorLoginWhenCodeIsValid() {
		AppUserAccountService appUserAccountService = mock(AppUserAccountService.class);
		AppUserDetailsService appUserDetailsService = mock(AppUserDetailsService.class);
		TwoFactorLoginService twoFactorLoginService = mock(TwoFactorLoginService.class);
		AuthWebController controller = new AuthWebController(appUserAccountService, appUserDetailsService,
				twoFactorLoginService, new GoogleLoginStatus(authProps(false), "", ""));
		MockHttpServletRequest request = requestWithPendingUser();

		when(twoFactorLoginService.verify("admin", "123456", "127.0.0.1", null))
				.thenReturn(TwoFactorLoginResult.SUCCESS);
		when(appUserDetailsService.loadUserByUsername("admin"))
				.thenReturn(User.withUsername("admin").password("hash").roles("ADMIN").build());

		String view = controller.verifyTwoFactor("123456", request, new ExtendedModelMap());
		HttpSession session = request.getSession();

		Assertions.assertThat(view).isEqualTo("redirect:/app");
		Assertions.assertThat(session.getAttribute(SecurityConstants.PENDING_USERNAME)).isNull();
		Assertions.assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
				.isNotNull();
		Assertions.assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("admin");
		verify(twoFactorLoginService).verify("admin", "123456", "127.0.0.1", null);
	}

	@Test
	void authShouldRegisterFailureAndReRenderWhenTwoFactorCodeIsInvalid() {
		AppUserAccountService appUserAccountService = mock(AppUserAccountService.class);
		AppUserDetailsService appUserDetailsService = mock(AppUserDetailsService.class);
		TwoFactorLoginService twoFactorLoginService = mock(TwoFactorLoginService.class);
		AuthWebController controller = new AuthWebController(appUserAccountService, appUserDetailsService,
				twoFactorLoginService, new GoogleLoginStatus(authProps(false), "", ""));
		MockHttpServletRequest request = requestWithPendingUser();

		when(twoFactorLoginService.verify("admin", "000000", "127.0.0.1", null))
				.thenReturn(TwoFactorLoginResult.INVALID);

		ExtendedModelMap model = new ExtendedModelMap();
		String view = controller.verifyTwoFactor("000000", request, model);

		Assertions.assertThat(view).isEqualTo("auth/two-factor");
		Assertions.assertThat(model).containsEntry("error", true).containsEntry("username", "admin");
		verify(appUserDetailsService, never()).loadUserByUsername(any());
	}

	/**
	 * Mirrors the login-page lockout: once {@link AccountLockService} has locked
	 * the account (eg. from earlier failed attempts on this same 2FA step), the
	 * code must not even be checked - otherwise the lock could be bypassed by
	 * simply not exceeding the threshold in a single burst, and it would extend the
	 * lock on every retry instead of expiring on schedule.
	 */
	@Test
	void authShouldRedirectToLoginWithoutCheckingCodeWhenAccountIsLocked() {
		AppUserAccountService appUserAccountService = mock(AppUserAccountService.class);
		AppUserDetailsService appUserDetailsService = mock(AppUserDetailsService.class);
		TwoFactorLoginService twoFactorLoginService = mock(TwoFactorLoginService.class);
		AuthWebController controller = new AuthWebController(appUserAccountService, appUserDetailsService,
				twoFactorLoginService, new GoogleLoginStatus(authProps(false), "", ""));
		MockHttpServletRequest request = requestWithPendingUser();

		when(twoFactorLoginService.verify("admin", "123456", "127.0.0.1", null))
				.thenReturn(TwoFactorLoginResult.LOCKED);

		String view = controller.verifyTwoFactor("123456", request, new ExtendedModelMap());

		Assertions.assertThat(view).isEqualTo("redirect:/login?locked");
		Assertions.assertThat(request.getSession().getAttribute(SecurityConstants.PENDING_USERNAME)).isNull();
		verify(appUserDetailsService, never()).loadUserByUsername(any());
	}

	@Test
	void authShouldChangePasswordFromLoginScreen() {
		AppUserAccountService appUserAccountService = mock(AppUserAccountService.class);
		AppUserDetailsService appUserDetailsService = mock(AppUserDetailsService.class);
		TwoFactorLoginService twoFactorLoginService = mock(TwoFactorLoginService.class);
		AuthWebController controller = new AuthWebController(appUserAccountService, appUserDetailsService,
				twoFactorLoginService, new GoogleLoginStatus(authProps(false), "", ""));

		String view = controller.changePassword("admin@example.com", "old", "newSecret", "newSecret",
				new RedirectAttributesModelMap());

		Assertions.assertThat(view).isEqualTo("redirect:/login");
		verify(appUserAccountService).changePassword("admin@example.com", "old", "newSecret");
	}

	private MockHttpServletRequest requestWithPendingUser() {
		MockHttpSession session = new MockHttpSession();
		MockHttpServletRequest request = new MockHttpServletRequest();

		session.setAttribute(SecurityConstants.PENDING_USERNAME, "admin");
		request.setSession(session);

		return request;
	}

	private NimbusFileManagerProperties authProps(boolean googleEnabled) {
		return new NimbusFileManagerProperties(null, null, null,
				new Security(0, 0, 0, googleEnabled, "admin", "admin", null), null);
	}
}