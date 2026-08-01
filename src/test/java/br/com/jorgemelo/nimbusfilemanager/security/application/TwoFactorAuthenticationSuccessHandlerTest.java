package br.com.jorgemelo.nimbusfilemanager.security.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import br.com.jorgemelo.nimbusfilemanager.security.application.constants.SecurityConstants;
import br.com.jorgemelo.nimbusfilemanager.security.domain.model.AppUser;
import br.com.jorgemelo.nimbusfilemanager.security.domain.repository.AppUserRepository;

class TwoFactorAuthenticationSuccessHandlerTest {

	private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
	private final UserAccessLogService userAccessLogService = mock(UserAccessLogService.class);
	private final AccountLockService accountLockService = mock(AccountLockService.class);
	private final TwoFactorAuthenticationSuccessHandler handler = new TwoFactorAuthenticationSuccessHandler(
			appUserRepository, userAccessLogService, accountLockService);

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void shouldDeferToTwoFactorAndNotResetLockCounterYetWhenEnabled() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();

		MockHttpServletResponse response = new MockHttpServletResponse();

		AppUser user = AppUser.builder().username("admin").twoFactorEnabled(true).build();

		var authentication = new TestingAuthenticationToken("admin", "pw",
				List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

		when(appUserRepository.findByUsername("admin")).thenReturn(Optional.of(user));

		handler.onAuthenticationSuccess(request, response, authentication);

		Assertions.assertThat(response.getRedirectedUrl()).isEqualTo("/login/2fa");
		Assertions.assertThat(request.getSession().getAttribute(SecurityConstants.PENDING_USERNAME)).isEqualTo("admin");
		Assertions.assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

		verify(accountLockService, never()).registerSuccess(any());
	}

	@Test
	void shouldResetLockCounterAndCompleteLoginWhenTwoFactorIsDisabled() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();

		MockHttpServletResponse response = new MockHttpServletResponse();

		AppUser user = AppUser.builder().username("admin").twoFactorEnabled(false).build();

		var authentication = new TestingAuthenticationToken("admin", "pw",
				List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

		when(appUserRepository.findByUsername("admin")).thenReturn(Optional.of(user));

		handler.onAuthenticationSuccess(request, response, authentication);

		verify(accountLockService).registerSuccess("admin");
		verify(userAccessLogService).recordAccess(eq("admin"), eq(SecurityConstants.LOGIN_SUCCESS), eq("SUCCESS"),
				eq("127.0.0.1"), isNull(), any());
	}
}