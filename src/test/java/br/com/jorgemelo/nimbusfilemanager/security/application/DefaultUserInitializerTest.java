package br.com.jorgemelo.nimbusfilemanager.security.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import br.com.jorgemelo.nimbusfilemanager.security.domain.model.AppUser;
import br.com.jorgemelo.nimbusfilemanager.security.domain.repository.AppUserRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Security;

class DefaultUserInitializerTest {

	private final FirstAccessCredential credential = mock(FirstAccessCredential.class);

	@Test
	void shouldRequirePasswordChangeWhenCreatingUserWithDefaultPassword() {
		AppUserRepository repository = mock(AppUserRepository.class);

		PasswordEncoder encoder = mock(PasswordEncoder.class);

		when(encoder.encode("admin")).thenReturn("hash");
		when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		new DefaultUserInitializer(repository, encoder, credential, props("admin@example.com", "admin")).run(null);

		var captor = ArgumentCaptor.forClass(AppUser.class);

		verify(repository).save(captor.capture());

		Assertions.assertThat(captor.getValue().getPasswordChangeRequired()).isTrue();
	}

	@Test
	void shouldRequireChangeEvenForAStrongConfiguredDefaultPassword() {
		AppUserRepository repository = mock(AppUserRepository.class);

		PasswordEncoder encoder = mock(PasswordEncoder.class);

		when(encoder.encode("strongSecret")).thenReturn("hash");

		new DefaultUserInitializer(repository, encoder, credential, props("admin@example.com", "strongSecret"))
				.run(null);

		var captor = ArgumentCaptor.forClass(AppUser.class);

		verify(repository).save(captor.capture());

		// the configured default is a known/published value, so a change is required
		// even if strong
		Assertions.assertThat(captor.getValue().getPasswordChangeRequired()).isTrue();
	}

	@Test
	void shouldMarkExistingLegacyAdminStillUsingDefaultPassword() {
		AppUserRepository repository = mock(AppUserRepository.class);

		PasswordEncoder encoder = mock(PasswordEncoder.class);

		AppUser admin = AppUser.builder().username("admin@example.com").passwordHash("legacy-hash")
				.passwordChangeRequired(false).build();

		when(repository.count()).thenReturn(1L);
		when(repository.findByUsernameIgnoreCase("admin@example.com")).thenReturn(Optional.of(admin));
		when(encoder.matches("configured-value", "legacy-hash")).thenReturn(true);

		new DefaultUserInitializer(repository, encoder, credential, props("admin@example.com", "configured-value"))
				.run(null);

		Assertions.assertThat(admin.getPasswordChangeRequired()).isTrue();

		verify(repository).save(admin);
	}

	@Test
	void shouldLeaveExistingAdminWithChangedPasswordUntouched() {
		AppUserRepository repository = mock(AppUserRepository.class);

		PasswordEncoder encoder = mock(PasswordEncoder.class);

		AppUser admin = AppUser.builder().username("admin@example.com").passwordHash("strong-hash").build();

		when(repository.count()).thenReturn(1L);
		when(repository.findByUsernameIgnoreCase("admin@example.com")).thenReturn(Optional.of(admin));

		new DefaultUserInitializer(repository, encoder, credential, props("admin@example.com", "configured-value"))
				.run(null);

		verify(repository, never()).save(any());
	}

	/**
	 * The default case of a fresh installation: nothing configured, so the password
	 * is generated here and never equals the one of any other installation. The
	 * change on first login stays required - the generated value is single-use.
	 */
	@Test
	void generatesAPerInstallationPasswordWhenNoneIsConfigured() {
		AppUserRepository repository = mock(AppUserRepository.class);

		PasswordEncoder encoder = mock(PasswordEncoder.class);

		when(credential.generate()).thenReturn("generated-value");
		when(encoder.encode("generated-value")).thenReturn("hash");

		new DefaultUserInitializer(repository, encoder, credential, props("admin@example.com", "")).run(null);

		var captor = ArgumentCaptor.forClass(AppUser.class);

		verify(repository).save(captor.capture());

		Assertions.assertThat(captor.getValue().getPasswordHash()).isEqualTo("hash");
		Assertions.assertThat(captor.getValue().getPasswordChangeRequired()).isTrue();

		verify(credential).publish("admin@example.com", "generated-value");
	}

	/**
	 * Provisioning a container or a CI environment needs a password known ahead of
	 * time, so a configured value still wins - and nothing is generated or
	 * published for it.
	 */
	@Test
	void keepsUsingAConfiguredPasswordWithoutPublishingAnything() {
		AppUserRepository repository = mock(AppUserRepository.class);

		PasswordEncoder encoder = mock(PasswordEncoder.class);

		when(encoder.encode("provisioned")).thenReturn("hash");

		new DefaultUserInitializer(repository, encoder, credential, props("admin@example.com", "provisioned"))
				.run(null);

		verify(repository).save(any());
		verify(credential, never()).generate();
		verify(credential, never()).publish(any(), any());
	}

	/**
	 * With no configured password there is nothing to compare an existing hash
	 * against, so the legacy check has to stay away from the account instead of
	 * guessing.
	 */
	@Test
	void leavesAnExistingAdminAloneWhenNoPasswordIsConfigured() {
		AppUserRepository repository = mock(AppUserRepository.class);

		PasswordEncoder encoder = mock(PasswordEncoder.class);

		when(repository.count()).thenReturn(1L);

		new DefaultUserInitializer(repository, encoder, credential, props("admin@example.com", "")).run(null);

		verify(repository, never()).findByUsernameIgnoreCase(any());
		verify(repository, never()).save(any());
	}

	/**
	 * Absent and empty are the same intent - nobody provisioned a password - and a
	 * null must not slip through as if it were one. It reaches here whenever the
	 * environment variable is simply not set.
	 */
	@Test
	void treatsAnAbsentPasswordLikeAnEmptyOne() {
		AppUserRepository repository = mock(AppUserRepository.class);

		PasswordEncoder encoder = mock(PasswordEncoder.class);

		when(credential.generate()).thenReturn("generated-value");
		when(encoder.encode("generated-value")).thenReturn("hash");

		new DefaultUserInitializer(repository, encoder, credential, props("admin@example.com", null)).run(null);

		verify(repository).save(any());
		verify(credential).publish("admin@example.com", "generated-value");
	}

	@Test
	void leavesAnExistingAdminAloneWhenNoPasswordIsProvisionedAtAll() {
		AppUserRepository repository = mock(AppUserRepository.class);

		when(repository.count()).thenReturn(1L);

		new DefaultUserInitializer(repository, mock(PasswordEncoder.class), credential,
				props("admin@example.com", null)).run(null);

		verify(repository, never()).findByUsernameIgnoreCase(any());
	}

	/**
	 * The way back in after a restore. The backup carries the users of the
	 * installation it came from, so whoever restores their catalog onto a new
	 * machine is locked out by a password they may never have known - and the only
	 * way out used to be editing rows by hand.
	 */
	@Test
	void resetsTheAdministratorWhenAResetPasswordIsConfigured() {
		AppUserRepository repository = mock(AppUserRepository.class);
		PasswordEncoder encoder = mock(PasswordEncoder.class);

		AppUser restored = AppUser.builder().username("admin@example.com").passwordHash("hash-from-another-install")
				.passwordChangeRequired(false).build();

		when(repository.count()).thenReturn(1L);
		when(repository.findByUsernameIgnoreCase("admin@example.com")).thenReturn(Optional.of(restored));
		when(encoder.encode("back-in")).thenReturn("new-hash");

		new DefaultUserInitializer(repository, encoder, credential,
				props("admin@example.com", "", "back-in")).run(null);

		Assertions.assertThat(restored.getPasswordHash()).isEqualTo("new-hash");
		Assertions.assertThat(restored.getPasswordChangeRequired()).isTrue();

		verify(repository).save(restored);
	}

	/**
	 * The lockout goes with the password: the attempts that led someone to reset it
	 * are usually the ones that locked the account, and leaving the timer running
	 * would refuse the decision just taken.
	 */
	@Test
	void clearsTheLockoutWhenItResetsThePassword() {
		AppUserRepository repository = mock(AppUserRepository.class);
		PasswordEncoder encoder = mock(PasswordEncoder.class);

		AppUser locked = AppUser.builder().username("admin@example.com").passwordHash("old").failedLoginAttempts(5)
				.lockedUntil(LocalDateTime.now().plusMinutes(30)).build();

		when(repository.count()).thenReturn(1L);
		when(repository.findByUsernameIgnoreCase("admin@example.com")).thenReturn(Optional.of(locked));
		when(encoder.encode("back-in")).thenReturn("new-hash");

		new DefaultUserInitializer(repository, encoder, credential,
				props("admin@example.com", "", "back-in")).run(null);

		Assertions.assertThat(locked.getLockedUntil()).isNull();
		Assertions.assertThat(locked.getFailedLoginAttempts()).isZero();
	}

	/**
	 * The reset is a decision taken once, not a consequence of provisioning: a
	 * container restarting with a default password set must never undo the password
	 * its owner has since chosen. Only the dedicated property does that.
	 */
	@Test
	void leavesTheAdministratorAloneWhenOnlyADefaultPasswordIsConfigured() {
		AppUserRepository repository = mock(AppUserRepository.class);
		PasswordEncoder encoder = mock(PasswordEncoder.class);

		AppUser chosen = AppUser.builder().username("admin@example.com").passwordHash("password-the-owner-chose")
				.build();

		when(repository.count()).thenReturn(1L);
		when(repository.findByUsernameIgnoreCase("admin@example.com")).thenReturn(Optional.of(chosen));

		new DefaultUserInitializer(repository, encoder, credential,
				props("admin@example.com", "provisioned", null)).run(null);

		Assertions.assertThat(chosen.getPasswordHash()).isEqualTo("password-the-owner-chose");

		verify(repository, never()).save(any());
	}

	/**
	 * The reset names an account. A restored catalog whose administrator is called
	 * something else leaves nothing to reset, and inventing the user would hand out
	 * an account the backup never had.
	 */
	@Test
	void resetsNothingWhenTheConfiguredUsernameIsNotInTheRestoredCatalog() {
		AppUserRepository repository = mock(AppUserRepository.class);
		PasswordEncoder encoder = mock(PasswordEncoder.class);

		when(repository.count()).thenReturn(1L);
		when(repository.findByUsernameIgnoreCase("admin@example.com")).thenReturn(Optional.empty());

		new DefaultUserInitializer(repository, encoder, credential,
				props("admin@example.com", "", "back-in")).run(null);

		verify(repository, never()).save(any());
		verify(encoder, never()).encode(any());
	}

	private NimbusFileManagerProperties props(String username, String password) {
		return props(username, password, null);
	}

	private NimbusFileManagerProperties props(String username, String password, String resetPassword) {
		return new NimbusFileManagerProperties(null, null, null,
				new Security(0, 0, 0, true, username, password, resetPassword), null);
	}
}