package br.com.jorgemelo.nimbusfilemanager.security.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

	@Test
	void shouldRequirePasswordChangeWhenCreatingUserWithDefaultPassword() {
		AppUserRepository repository = mock(AppUserRepository.class);

		PasswordEncoder encoder = mock(PasswordEncoder.class);

		when(encoder.encode("admin")).thenReturn("hash");
		when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		new DefaultUserInitializer(repository, encoder, props("admin@example.com", "admin")).run(null);

		var captor = ArgumentCaptor.forClass(AppUser.class);

		verify(repository).save(captor.capture());

		Assertions.assertThat(captor.getValue().getPasswordChangeRequired()).isTrue();
	}

	@Test
	void shouldRequireChangeEvenForAStrongConfiguredDefaultPassword() {
		AppUserRepository repository = mock(AppUserRepository.class);

		PasswordEncoder encoder = mock(PasswordEncoder.class);

		when(encoder.encode("strongSecret")).thenReturn("hash");

		new DefaultUserInitializer(repository, encoder, props("admin@example.com", "strongSecret")).run(null);

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

		new DefaultUserInitializer(repository, encoder, props("admin@example.com", "configured-value")).run(null);

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

		new DefaultUserInitializer(repository, encoder, props("admin@example.com", "configured-value")).run(null);

		verify(repository, never()).save(any());
	}

	private NimbusFileManagerProperties props(String username, String password) {
		return new NimbusFileManagerProperties(null, null, null, null, null, null,
				new Security(0, 0, 0, true, username, password), null);
	}
}