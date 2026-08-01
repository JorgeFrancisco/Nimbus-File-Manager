package br.com.jorgemelo.nimbusfilemanager.security.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import br.com.jorgemelo.nimbusfilemanager.security.domain.enums.Role;
import br.com.jorgemelo.nimbusfilemanager.security.domain.model.AppUser;
import br.com.jorgemelo.nimbusfilemanager.security.domain.repository.AppUserRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.ClockHolder;

class AppUserDetailsServiceTest {

	private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
	private final AppUserDetailsService service = new AppUserDetailsService(appUserRepository);

	@Test
	void loadUserByUsernameShouldMapRoleAndBeUnlockedByDefault() {
		AppUser user = AppUser.builder().username("admin@example.com").passwordHash("hash").role(Role.ADMIN)
				.enabled(true).build();

		when(appUserRepository.findByUsernameIgnoreCase("admin@example.com")).thenReturn(Optional.of(user));

		UserDetails details = service.loadUserByUsername("admin@example.com");

		Assertions.assertThat(details.getUsername()).isEqualTo("admin@example.com");
		Assertions.assertThat(details.getPassword()).isEqualTo("hash");
		Assertions.assertThat(details.isEnabled()).isTrue();
		Assertions.assertThat(details.isAccountNonLocked()).isTrue();
		Assertions.assertThat(details.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_ADMIN");
	}

	/**
	 * {@code DaoAuthenticationProvider}'s default pre-authentication checks throw
	 * {@link org.springframework.security.authentication.LockedException} as soon
	 * as {@code isAccountNonLocked()} is false, <i>before</i> comparing the
	 * password - this is what lets {@link AccountLockService}-driven lockouts block
	 * further password guesses without a bespoke check in every login path.
	 */
	@Test
	void loadUserByUsernameShouldReportAccountLockedWhileLockedUntilIsInTheFuture() {
		AppUser user = AppUser.builder().username("admin@example.com").passwordHash("hash").role(Role.ADMIN)
				.enabled(true).lockedUntil(LocalDateTime.now(ClockHolder.clock()).plusMinutes(5)).build();

		when(appUserRepository.findByUsernameIgnoreCase("admin@example.com")).thenReturn(Optional.of(user));

		UserDetails details = service.loadUserByUsername("admin@example.com");

		Assertions.assertThat(details.isAccountNonLocked()).isFalse();
	}

	@Test
	void loadUserByUsernameShouldReportAccountUnlockedOnceLockExpires() {
		AppUser user = AppUser.builder().username("admin@example.com").passwordHash("hash").role(Role.ADMIN)
				.enabled(true).lockedUntil(LocalDateTime.now(ClockHolder.clock()).minusSeconds(1)).build();

		when(appUserRepository.findByUsernameIgnoreCase("admin@example.com")).thenReturn(Optional.of(user));

		UserDetails details = service.loadUserByUsername("admin@example.com");

		Assertions.assertThat(details.isAccountNonLocked()).isTrue();
	}

	@Test
	void loadUserByUsernameShouldRejectUnknownUsername() {
		when(appUserRepository.findByUsernameIgnoreCase("missing@example.com")).thenReturn(Optional.empty());

		Assertions.assertThatThrownBy(() -> service.loadUserByUsername("missing@example.com"))
				.isInstanceOf(UsernameNotFoundException.class);
	}
}