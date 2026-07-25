package br.com.jorgemelo.nimbusfilemanager.security.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import br.com.jorgemelo.nimbusfilemanager.security.application.constants.AccessMessages;
import br.com.jorgemelo.nimbusfilemanager.security.application.constants.SecurityConstants;
import br.com.jorgemelo.nimbusfilemanager.security.domain.model.AppUser;
import br.com.jorgemelo.nimbusfilemanager.security.domain.repository.AppUserRepository;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.ClockHolder;

class AccountLockServiceTest {

	private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
	private final AppSettingService appSettingService = mock(AppSettingService.class);
	private final UserAccessLogService userAccessLogService = mock(UserAccessLogService.class);
	private final AccountLockService service = new AccountLockService(appUserRepository, appSettingService,
			userAccessLogService, Clock.systemDefaultZone());

	private AppUser user(long id, int attempts, LocalDateTime lockedUntil) {
		AppUser user = AppUser.builder().id(id).username("admin@example.com").failedLoginAttempts(attempts)
				.lockedUntil(lockedUntil).build();

		when(appUserRepository.findByUsernameIgnoreCase("admin@example.com")).thenReturn(Optional.of(user));

		return user;
	}

	@Test
	void registerFailureIncrementsAtomicallyWithoutLockingBelowThreshold() {
		user(1L, 2, null);

		when(appSettingService.intValue(eq(SettingsConstants.MAX_FAILED_LOGIN_ATTEMPTS), ArgumentMatchers.anyInt()))
				.thenReturn(5);
		when(appUserRepository.applyLockoutIfThresholdReached(eq(1L), eq(5), any(), any())).thenReturn(0);

		service.registerFailure("admin@example.com", "127.0.0.1", "JUnit");

		verify(appUserRepository).incrementFailedAttempts(eq(1L), any(LocalDateTime.class));
		verify(appUserRepository).applyLockoutIfThresholdReached(eq(1L), eq(5), any(), any());
		verify(userAccessLogService, never()).recordAccess(anyString(), eq(SecurityConstants.ACCOUNT_LOCKED),
				anyString(), any(), any(), anyString());
	}

	@Test
	void registerFailureLocksAndLogsWhenTheAtomicLockoutApplies() {
		user(1L, 4, null);

		when(appSettingService.intValue(eq(SettingsConstants.MAX_FAILED_LOGIN_ATTEMPTS), ArgumentMatchers.anyInt()))
				.thenReturn(5);
		when(appSettingService.intValue(eq(SettingsConstants.LOCKOUT_DURATION_MINUTES), ArgumentMatchers.anyInt()))
				.thenReturn(15);
		when(appUserRepository.applyLockoutIfThresholdReached(eq(1L), eq(5), any(), any())).thenReturn(1);

		service.registerFailure("admin@example.com", "203.0.113.9", "JUnit");

		verify(appUserRepository).incrementFailedAttempts(eq(1L), any(LocalDateTime.class));
		verify(userAccessLogService).recordAccess("admin@example.com", SecurityConstants.ACCOUNT_LOCKED, "FAILURE",
				"203.0.113.9", "JUnit", AccessMessages.ACCOUNT_LOCKED);
	}

	@Test
	void registerFailureIsNoOpWhileAlreadyLocked() {
		user(1L, 0, LocalDateTime.now(ClockHolder.clock()).plusMinutes(5));

		service.registerFailure("admin@example.com", "127.0.0.1", "JUnit");

		verify(appUserRepository, never()).incrementFailedAttempts(any(), any());
		verify(appUserRepository, never()).applyLockoutIfThresholdReached(any(), ArgumentMatchers.anyInt(), any(),
				any());
		verify(userAccessLogService, never()).recordAccess(anyString(), eq(SecurityConstants.ACCOUNT_LOCKED),
				anyString(), any(), any(), anyString());
	}

	@Test
	void registerFailureToleratesMissingClientInfo() {
		user(1L, 0, null);

		when(appSettingService.intValue(eq(SettingsConstants.MAX_FAILED_LOGIN_ATTEMPTS), ArgumentMatchers.anyInt()))
				.thenReturn(5);

		service.registerFailure("admin@example.com", null, null);

		verify(appUserRepository).incrementFailedAttempts(eq(1L), any(LocalDateTime.class));
	}

	@Test
	void registerFailureIgnoresUnknownUsername() {
		when(appUserRepository.findByUsernameIgnoreCase("missing@example.com")).thenReturn(Optional.empty());

		Assertions.assertThatCode(() -> service.registerFailure("missing@example.com", null, null))
				.doesNotThrowAnyException();

		verify(appUserRepository, never()).incrementFailedAttempts(any(), any());
		verify(userAccessLogService, never()).recordAccess(anyString(), anyString(), anyString(), any(), any(),
				anyString());
	}

	@Test
	void registerSuccessClearsFailuresAtomically() {
		user(1L, 3, LocalDateTime.now().minusMinutes(1));

		service.registerSuccess("admin@example.com");

		verify(appUserRepository).clearFailuresOnSuccess(eq(1L), any(LocalDateTime.class));
	}
}