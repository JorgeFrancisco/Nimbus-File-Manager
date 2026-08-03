package br.com.jorgemelo.nimbusfilemanager.security.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
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
import br.com.jorgemelo.nimbusfilemanager.shared.domain.ClockHolder;

class AppUserAccountServiceTest {

	@Test
	void createUserShouldNormalizeEmailRoleAndEncodePassword() {
		AppUserRepository repository = mock(AppUserRepository.class);

		PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

		AppUserAccountService service = new AppUserAccountService(repository, passwordEncoder,
				mock(AccountLockService.class), Clock.systemDefaultZone());

		when(passwordEncoder.encode("secret1")).thenReturn("hash");
		when(repository.save(ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));

		AppUser user = service.createUser(" Admin@Example.COM ", " Admin ", "secret1", "admin");

		Assertions.assertThat(user.getUsername()).isEqualTo("admin@example.com");
		Assertions.assertThat(user.getDisplayName()).isEqualTo("Admin");
		Assertions.assertThat(user.getPasswordHash()).isEqualTo("hash");
		Assertions.assertThat(user.getRole()).isEqualTo(Role.ADMIN);
	}

	@Test
	void registerShouldCreateDisabledUserWithConfirmationToken() {
		AppUserRepository repository = mock(AppUserRepository.class);

		PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

		AppUserAccountService service = new AppUserAccountService(repository, passwordEncoder,
				mock(AccountLockService.class), Clock.systemDefaultZone());

		when(passwordEncoder.encode("secret1")).thenReturn("hash");
		when(repository.save(ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));

		AppUser user = service.register("new@example.com", "New User", "secret1");

		Assertions.assertThat(user.getUsername()).isEqualTo("new@example.com");
		Assertions.assertThat(user.getRole()).isEqualTo(Role.USER);
		Assertions.assertThat(user.getEnabled()).isFalse();
		Assertions.assertThat(user.getConfirmationToken()).isNotBlank();
		Assertions.assertThat(user.getConfirmationTokenExpiresAt()).isAfter(LocalDateTime.now());
	}

	@Test
	void registerEnforcesTheMinimumPasswordLengthBoundary() {
		AppUserRepository repository = mock(AppUserRepository.class);

		PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

		AppUserAccountService service = new AppUserAccountService(repository, passwordEncoder,
				mock(AccountLockService.class), Clock.systemDefaultZone());

		when(repository.findByUsernameIgnoreCase(ArgumentMatchers.anyString())).thenReturn(Optional.empty());
		when(passwordEncoder.encode(ArgumentMatchers.anyString())).thenReturn("hash");
		when(repository.save(ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));

		// 5 characters is below the 6-character minimum and must be rejected before any
		// save.
		Assertions.assertThatThrownBy(() -> service.register("a@example.com", "A", "12345"))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("A senha deve ter pelo menos 6 caracteres.");

		// Exactly 6 characters sits on the boundary and must be accepted.
		Assertions.assertThat(service.register("b@example.com", "B", "123456")).isNotNull();

		verify(repository).save(ArgumentMatchers.any());
	}

	@Test
	void registerShouldRejectWhenEmailAlreadyBelongsToAnEnabledUser() {
		AppUserRepository repository = mock(AppUserRepository.class);

		AppUserAccountService service = new AppUserAccountService(repository, mock(PasswordEncoder.class),
				mock(AccountLockService.class), Clock.systemDefaultZone());

		AppUser existing = AppUser.builder().username("taken@example.com").enabled(true).build();

		when(repository.findByUsernameIgnoreCase("taken@example.com")).thenReturn(Optional.of(existing));

		Assertions.assertThatThrownBy(() -> service.register("taken@example.com", "Someone", "secret1"))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("E-mail já cadastrado.");
	}

	/**
	 * Without this, a confirmation link that expires after 24h (see
	 * CONFIRMATION_TOKEN_VALID_HOURS) would leave the account permanently stuck:
	 * confirming fails (token expired) and registering again would otherwise fail
	 * too ("E-mail já cadastrado."), with no self-service way out.
	 */
	@Test
	void registerShouldRefreshPasswordAndTokenWhenEmailBelongsToAnUnconfirmedUser() {
		AppUserRepository repository = mock(AppUserRepository.class);

		PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

		AppUserAccountService service = new AppUserAccountService(repository, passwordEncoder,
				mock(AccountLockService.class), Clock.systemDefaultZone());

		AppUser existing = AppUser.builder().username("pending@example.com").displayName("Old Name")
				.passwordHash("oldHash").enabled(false).confirmationToken("old-token")
				.confirmationTokenExpiresAt(LocalDateTime.now().minusHours(1)).build();

		when(repository.findByUsernameIgnoreCase("pending@example.com")).thenReturn(Optional.of(existing));
		when(passwordEncoder.encode("newSecret")).thenReturn("newHash");
		when(repository.save(ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));

		AppUser user = service.register("pending@example.com", "New Name", "newSecret");

		Assertions.assertThat(user).isSameAs(existing);
		Assertions.assertThat(user.getDisplayName()).isEqualTo("New Name");
		Assertions.assertThat(user.getPasswordHash()).isEqualTo("newHash");
		Assertions.assertThat(user.getEnabled()).isFalse();
		Assertions.assertThat(user.getConfirmationToken()).isNotBlank().isNotEqualTo("old-token");
		Assertions.assertThat(user.getConfirmationTokenExpiresAt()).isAfter(LocalDateTime.now());
	}

	@Test
	void confirmRegistrationShouldEnableUserAndClearToken() {
		AppUserRepository repository = mock(AppUserRepository.class);

		AppUserAccountService service = new AppUserAccountService(repository, mock(PasswordEncoder.class),
				mock(AccountLockService.class), Clock.systemDefaultZone());

		AppUser user = AppUser.builder().username("new@example.com").enabled(false).confirmationToken("tok-123")
				.confirmationTokenExpiresAt(LocalDateTime.now().plusHours(1)).build();

		when(repository.findByConfirmationToken("tok-123")).thenReturn(Optional.of(user));

		AppUser confirmed = service.confirmRegistration("tok-123");

		Assertions.assertThat(confirmed.getEnabled()).isTrue();
		Assertions.assertThat(confirmed.getConfirmationToken()).isNull();
		Assertions.assertThat(confirmed.getConfirmationTokenExpiresAt()).isNull();
	}

	@Test
	void confirmRegistrationShouldRejectUnknownToken() {
		AppUserRepository repository = mock(AppUserRepository.class);

		AppUserAccountService service = new AppUserAccountService(repository, mock(PasswordEncoder.class),
				mock(AccountLockService.class), Clock.systemDefaultZone());

		when(repository.findByConfirmationToken("missing")).thenReturn(Optional.empty());

		Assertions.assertThatThrownBy(() -> service.confirmRegistration("missing"))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Token de confirmação inválido.");
	}

	@Test
	void confirmRegistrationShouldRejectExpiredToken() {
		AppUserRepository repository = mock(AppUserRepository.class);

		AppUserAccountService service = new AppUserAccountService(repository, mock(PasswordEncoder.class),
				mock(AccountLockService.class), Clock.systemDefaultZone());

		AppUser user = AppUser.builder().username("new@example.com").enabled(false).confirmationToken("tok-123")
				.confirmationTokenExpiresAt(LocalDateTime.now().minusMinutes(1)).build();

		when(repository.findByConfirmationToken("tok-123")).thenReturn(Optional.of(user));

		Assertions.assertThatThrownBy(() -> service.confirmRegistration("tok-123"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Token de confirmação expirado. Cadastre-se novamente.");
	}

	@Test
	void confirmRegistrationShouldRejectBlankToken() {
		AppUserRepository repository = mock(AppUserRepository.class);

		AppUserAccountService service = new AppUserAccountService(repository, mock(PasswordEncoder.class),
				mock(AccountLockService.class), Clock.systemDefaultZone());

		Assertions.assertThatThrownBy(() -> service.confirmRegistration(" "))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Token de confirmação inválido.");
	}

	@Test
	void changePasswordShouldRequireCurrentPassword() {
		AppUserRepository repository = mock(AppUserRepository.class);

		PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

		AccountLockService accountLockService = mock(AccountLockService.class);

		AppUserAccountService service = new AppUserAccountService(repository, passwordEncoder, accountLockService,
				Clock.systemDefaultZone());

		AppUser user = AppUser.builder().username("admin@example.com").passwordHash("oldHash")
				.passwordChangeRequired(true).build();

		when(repository.findByUsernameIgnoreCase("admin@example.com")).thenReturn(Optional.of(user));
		when(passwordEncoder.matches("old", "oldHash")).thenReturn(true);
		when(passwordEncoder.encode("newSecret")).thenReturn("newHash");

		service.changePassword("ADMIN@example.com", "old", "newSecret");

		Assertions.assertThat(user.getPasswordHash()).isEqualTo("newHash");
		Assertions.assertThat(user.getPasswordChangeRequired()).isFalse();

		verify(passwordEncoder).matches("old", "oldHash");
		verify(accountLockService).registerSuccess("admin@example.com");
	}

	@Test
	void changePasswordShouldRegisterFailureAndRejectWhenCurrentPasswordIsWrong() {
		AppUserRepository repository = mock(AppUserRepository.class);

		PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

		AccountLockService accountLockService = mock(AccountLockService.class);

		AppUserAccountService service = new AppUserAccountService(repository, passwordEncoder, accountLockService,
				Clock.systemDefaultZone());

		AppUser user = AppUser.builder().username("admin@example.com").passwordHash("hash").build();

		when(repository.findByUsernameIgnoreCase("admin@example.com")).thenReturn(Optional.of(user));
		when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

		Assertions.assertThatThrownBy(() -> service.changePassword("admin@example.com", "wrong", "newSecret"))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Senha atual inválida.");

		verify(accountLockService).registerFailure("admin@example.com", null, null);
	}

	@Test
	void changePasswordShouldRejectWithoutCheckingPasswordWhenAccountIsLocked() {
		AppUserRepository repository = mock(AppUserRepository.class);

		PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

		AccountLockService accountLockService = mock(AccountLockService.class);

		AppUserAccountService service = new AppUserAccountService(repository, passwordEncoder, accountLockService,
				Clock.systemDefaultZone());

		AppUser user = AppUser.builder().username("admin@example.com").passwordHash("hash")
				.lockedUntil(LocalDateTime.now(ClockHolder.clock()).plusMinutes(10)).build();

		when(repository.findByUsernameIgnoreCase("admin@example.com")).thenReturn(Optional.of(user));

		Assertions.assertThatThrownBy(() -> service.changePassword("admin@example.com", "hash", "newSecret"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bloqueada");

		verify(passwordEncoder, never()).matches(ArgumentMatchers.any(), ArgumentMatchers.any());
		verify(accountLockService, never()).registerFailure(ArgumentMatchers.any(), ArgumentMatchers.any(),
				ArgumentMatchers.any());
	}

	@Test
	void resetRequiredPasswordShouldSetNewPasswordAndClearFlagWithoutCurrentPassword() {
		AppUserRepository repository = mock(AppUserRepository.class);

		PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

		AccountLockService accountLockService = mock(AccountLockService.class);

		AppUserAccountService service = new AppUserAccountService(repository, passwordEncoder, accountLockService,
				Clock.systemDefaultZone());

		AppUser user = AppUser.builder().username("admin@example.com").passwordHash("defaultHash")
				.passwordChangeRequired(true).build();

		when(repository.findByUsernameIgnoreCase("admin@example.com")).thenReturn(Optional.of(user));
		when(passwordEncoder.matches("newSecret", "defaultHash")).thenReturn(false);
		when(passwordEncoder.encode("newSecret")).thenReturn("newHash");

		service.resetRequiredPassword("ADMIN@example.com", "newSecret");

		Assertions.assertThat(user.getPasswordHash()).isEqualTo("newHash");
		Assertions.assertThat(user.getPasswordChangeRequired()).isFalse();
	}

	/**
	 * An address nobody owns reaches the same call as one that does, so the
	 * refusal has to come from the lookup rather than from the password check -
	 * otherwise a caller could tell a registered address from an unregistered one
	 * by which complaint comes back.
	 */
	@Test
	void changePasswordShouldRejectAnAddressThatBelongsToNobody() {
		AppUserRepository repository = mock(AppUserRepository.class);

		PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

		AppUserAccountService service = new AppUserAccountService(repository, passwordEncoder,
				mock(AccountLockService.class), Clock.systemDefaultZone());

		when(repository.findByUsernameIgnoreCase("ghost@example.com")).thenReturn(Optional.empty());

		Assertions.assertThatThrownBy(() -> service.changePassword(" Ghost@Example.COM ", "old", "newSecret"))
				.isInstanceOf(IllegalArgumentException.class);

		verify(repository, never()).save(ArgumentMatchers.any());
	}

	@Test
	void resetRequiredPasswordShouldRejectAnAddressThatBelongsToNobody() {
		AppUserRepository repository = mock(AppUserRepository.class);

		PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

		AppUserAccountService service = new AppUserAccountService(repository, passwordEncoder,
				mock(AccountLockService.class), Clock.systemDefaultZone());

		when(repository.findByUsernameIgnoreCase("ghost@example.com")).thenReturn(Optional.empty());

		Assertions.assertThatThrownBy(() -> service.resetRequiredPassword("ghost@example.com", "newSecret"))
				.isInstanceOf(IllegalArgumentException.class);

		verify(repository, never()).save(ArgumentMatchers.any());
	}

	@Test
	void resetRequiredPasswordShouldRejectWhenChangeIsNotRequired() {
		AppUserRepository repository = mock(AppUserRepository.class);

		PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

		AccountLockService accountLockService = mock(AccountLockService.class);

		AppUserAccountService service = new AppUserAccountService(repository, passwordEncoder, accountLockService,
				Clock.systemDefaultZone());

		AppUser user = AppUser.builder().username("admin@example.com").passwordHash("hash")
				.passwordChangeRequired(false).build();

		when(repository.findByUsernameIgnoreCase("admin@example.com")).thenReturn(Optional.of(user));

		Assertions.assertThatThrownBy(() -> service.resetRequiredPassword("admin@example.com", "newSecret"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("não é obrigatória");
		Assertions.assertThat(user.getPasswordHash()).isEqualTo("hash");
	}

	@Test
	void resetRequiredPasswordShouldRejectReusingTheDefaultPassword() {
		AppUserRepository repository = mock(AppUserRepository.class);

		PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

		AccountLockService accountLockService = mock(AccountLockService.class);

		AppUserAccountService service = new AppUserAccountService(repository, passwordEncoder, accountLockService,
				Clock.systemDefaultZone());

		AppUser user = AppUser.builder().username("admin@example.com").passwordHash("defaultHash")
				.passwordChangeRequired(true).build();

		when(repository.findByUsernameIgnoreCase("admin@example.com")).thenReturn(Optional.of(user));
		when(passwordEncoder.matches("Mm#7Qv!9Lp$4Ks2", "defaultHash")).thenReturn(true);

		Assertions.assertThatThrownBy(() -> service.resetRequiredPassword("admin@example.com", "Mm#7Qv!9Lp$4Ks2"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("diferente");
	}

	@Test
	void changePasswordShouldRejectReusingCurrentPassword() {
		AppUserRepository repository = mock(AppUserRepository.class);

		PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

		AppUserAccountService service = new AppUserAccountService(repository, passwordEncoder,
				mock(AccountLockService.class), Clock.systemDefaultZone());

		AppUser user = AppUser.builder().username("admin@example.com").passwordHash("hash").passwordChangeRequired(true)
				.build();

		when(repository.findByUsernameIgnoreCase("admin@example.com")).thenReturn(Optional.of(user));
		when(passwordEncoder.matches("admin", "hash")).thenReturn(true);

		Assertions.assertThatThrownBy(() -> service.changePassword("admin@example.com", "admin", "admin"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deve ser diferente");
		Assertions.assertThat(user.getPasswordChangeRequired()).isTrue();
	}

	@Test
	void searchUsersShouldLimitPageSizeAndSearchByEmailOrName() {
		AppUserRepository repository = mock(AppUserRepository.class);

		AppUserAccountService service = new AppUserAccountService(repository, mock(PasswordEncoder.class),
				mock(AccountLockService.class), Clock.systemDefaultZone());

		AppUser user = AppUser.builder().username("admin@example.com").displayName("Admin").build();

		when(repository.findByUsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(
				ArgumentMatchers.eq("admin"), ArgumentMatchers.eq("admin"), ArgumentMatchers.any(Pageable.class)))
						.thenReturn(new PageImpl<>(List.of(user)));

		var page = service.searchUsers(" admin ", -1, 999);

		Assertions.assertThat(page.getContent()).containsExactly(user);

		verify(repository).findByUsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(
				ArgumentMatchers.eq("admin"), ArgumentMatchers.eq("admin"),
				ArgumentMatchers.argThat(pageable -> pageable.getPageNumber() == 0 && pageable.getPageSize() == 20));
	}
}