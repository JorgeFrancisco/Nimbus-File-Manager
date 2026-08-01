package br.com.jorgemelo.nimbusfilemanager.security.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

import br.com.jorgemelo.nimbusfilemanager.security.domain.enums.Role;
import br.com.jorgemelo.nimbusfilemanager.security.domain.model.AppUser;
import br.com.jorgemelo.nimbusfilemanager.security.domain.repository.AppUserRepository;

/**
 * Complements {@link AppUserAccountServiceTest}: OAuth upsert, validation error
 * branches (blank/invalid email, short password, duplicate), blank-query search
 * and page-size/role normalization.
 */
class AppUserAccountServiceExtraTest {

	private final AppUserRepository repository = mock(AppUserRepository.class);
	private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
	private final AccountLockService accountLockService = mock(AccountLockService.class);
	private final AppUserAccountService service = new AppUserAccountService(repository, passwordEncoder,
			accountLockService, Clock.systemDefaultZone());

	@Test
	void upsertOAuthUserUpdatesExistingUser() {
		AppUser existing = AppUser.builder().username("user@example.com").displayName("Old").enabled(false).build();

		when(repository.findByUsernameIgnoreCase("user@example.com")).thenReturn(Optional.of(existing));

		AppUser result = service.upsertOAuthUser("User@Example.com", "New Name");

		Assertions.assertThat(result).isSameAs(existing);
		Assertions.assertThat(result.getDisplayName()).isEqualTo("New Name");
		Assertions.assertThat(result.getEnabled()).isTrue();

		verify(repository, never()).save(any());
	}

	@Test
	void upsertOAuthUserCreatesNewUserWhenAbsent() {
		when(repository.findByUsernameIgnoreCase("user@example.com")).thenReturn(Optional.empty());
		when(passwordEncoder.encode(anyString())).thenReturn("hash");
		when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		AppUser result = service.upsertOAuthUser("user@example.com", null);

		Assertions.assertThat(result.getUsername()).isEqualTo("user@example.com");
		Assertions.assertThat(result.getDisplayName()).isEqualTo("user@example.com");
		Assertions.assertThat(result.getRole()).isEqualTo(Role.USER);
		Assertions.assertThat(result.getEnabled()).isTrue();
	}

	@Test
	void createUserRejectsDuplicateEmail() {
		when(repository.existsByUsernameIgnoreCase("user@example.com")).thenReturn(true);

		Assertions.assertThatThrownBy(() -> service.createUser("user@example.com", "User", "secret1", "USER"))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("E-mail já cadastrado.");
	}

	@Test
	void createUserDefaultsUnknownRoleToUser() {
		when(passwordEncoder.encode(anyString())).thenReturn("hash");
		when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		AppUser user = service.createUser("user@example.com", "User", "secret1", "superuser");

		Assertions.assertThat(user.getRole()).isEqualTo(Role.USER);
	}

	@Test
	void createUserRejectsShortPassword() {
		Assertions.assertThatThrownBy(() -> service.createUser("user@example.com", "User", "123", "USER"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pelo menos 6");
	}

	@Test
	void registerRejectsBlankEmail() {
		Assertions.assertThatThrownBy(() -> service.register("  ", "User", "secret1"))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("E-mail obrigatório.");
	}

	@Test
	void registerRejectsEmailWithoutAtSign() {
		when(repository.findByUsernameIgnoreCase("invalid")).thenReturn(Optional.empty());

		Assertions.assertThatThrownBy(() -> service.register("invalid", "User", "secret1"))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("E-mail inválido.");
	}

	@Test
	void searchUsersReturnsFullPageWhenQueryIsBlank() {
		AppUser user = AppUser.builder().username("user@example.com").build();

		when(repository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(user)));

		var page = service.searchUsers("   ", 0, 50);

		Assertions.assertThat(page.getContent()).containsExactly(user);

		verify(repository).findAll(ArgumentMatchers.<Pageable>argThat(pageable -> pageable.getPageSize() == 50));
	}

	@Test
	void searchUsersAcceptsHundredPageSize() {
		when(repository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

		service.searchUsers(null, 0, 100);

		verify(repository).findAll(ArgumentMatchers.<Pageable>argThat(pageable -> pageable.getPageSize() == 100));
	}
}