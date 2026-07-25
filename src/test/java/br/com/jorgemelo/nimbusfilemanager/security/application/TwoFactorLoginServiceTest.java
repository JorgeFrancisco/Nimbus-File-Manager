package br.com.jorgemelo.nimbusfilemanager.security.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.security.application.constants.AccessMessages;
import br.com.jorgemelo.nimbusfilemanager.security.application.constants.SecurityConstants;
import br.com.jorgemelo.nimbusfilemanager.security.domain.enums.Role;
import br.com.jorgemelo.nimbusfilemanager.security.domain.model.AppUser;
import br.com.jorgemelo.nimbusfilemanager.security.domain.repository.AppUserRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.ClockHolder;

class TwoFactorLoginServiceTest {

	private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
	private final TwoFactorService twoFactorService = mock(TwoFactorService.class);
	private final AccountLockService accountLockService = mock(AccountLockService.class);
	private final UserAccessLogService userAccessLogService = mock(UserAccessLogService.class);
	private final TwoFactorLoginService service = new TwoFactorLoginService(appUserRepository, twoFactorService,
			accountLockService, userAccessLogService);

	private AppUser user(LocalDateTime lockedUntil) {
		AppUser user = AppUser.builder().username("admin").twoFactorSecret("SECRET").role(Role.ADMIN)
				.lockedUntil(lockedUntil).build();

		when(appUserRepository.findByUsername("admin")).thenReturn(Optional.of(user));

		return user;
	}

	@Test
	void returnsLockedAndRecordsFailureWithoutCheckingCodeWhenAccountIsLocked() {
		user(LocalDateTime.now(ClockHolder.clock()).plusMinutes(10));

		TwoFactorLoginResult result = service.verify("admin", "123456", "127.0.0.1", "JUnit");

		Assertions.assertThat(result).isEqualTo(TwoFactorLoginResult.LOCKED);
		verify(userAccessLogService).recordAccess("admin", SecurityConstants.LOGIN_2FA_FAILURE, "FAILURE",
				"127.0.0.1", "JUnit", AccessMessages.TWO_FACTOR_REJECTED_LOCKED);
		verify(twoFactorService, never()).verify(any(), any());
		verify(accountLockService, never()).registerFailure(any(), any(), any());
	}

	@Test
	void returnsInvalidAndRegistersFailureWhenCodeIsWrong() {
		user(null);

		when(twoFactorService.verify("SECRET", "000000")).thenReturn(false);

		TwoFactorLoginResult result = service.verify("admin", "000000", "127.0.0.1", "JUnit");

		Assertions.assertThat(result).isEqualTo(TwoFactorLoginResult.INVALID);
		verify(accountLockService).registerFailure("admin", "127.0.0.1", "JUnit");
		verify(userAccessLogService).recordAccess("admin", SecurityConstants.LOGIN_2FA_FAILURE, "FAILURE",
				"127.0.0.1", "JUnit", AccessMessages.INVALID_TWO_FACTOR_CODE);
		verify(accountLockService, never()).registerSuccess(anyString());
	}

	@Test
	void returnsSuccessAndRegistersSuccessWhenCodeIsValid() {
		user(null);

		when(twoFactorService.verify("SECRET", "123456")).thenReturn(true);

		TwoFactorLoginResult result = service.verify("admin", "123456", "127.0.0.1", "JUnit");

		Assertions.assertThat(result).isEqualTo(TwoFactorLoginResult.SUCCESS);
		verify(accountLockService).registerSuccess("admin");
		verify(userAccessLogService).recordAccess("admin", SecurityConstants.LOGIN_2FA_SUCCESS, "SUCCESS",
				"127.0.0.1", "JUnit", AccessMessages.TWO_FACTOR_COMPLETED);
		verify(accountLockService, never()).registerFailure(any(), any(), any());
	}

	@Test
	void raisesWhenPendingUsernameHasNoMatchingAccount() {
		when(appUserRepository.findByUsername("ghost")).thenReturn(Optional.empty());

		Assertions.assertThatThrownBy(() -> service.verify("ghost", "123456", "127.0.0.1", "JUnit"))
				.isInstanceOf(NoSuchElementException.class);
	}
}