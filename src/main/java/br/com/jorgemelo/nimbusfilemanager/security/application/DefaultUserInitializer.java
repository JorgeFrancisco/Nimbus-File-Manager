package br.com.jorgemelo.nimbusfilemanager.security.application;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.security.domain.enums.Role;
import br.com.jorgemelo.nimbusfilemanager.security.domain.model.AppUser;
import br.com.jorgemelo.nimbusfilemanager.security.domain.repository.AppUserRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates the administrator of an empty installation.
 *
 * <p>
 * With no password configured - the default - one is generated for this
 * installation and shown once by {@link FirstAccessCredential}. A configured
 * password still wins, because provisioning a container or a CI environment
 * needs a value known in advance; it is treated as published either way, so the
 * change on first login stays required.
 */
@Slf4j
@Component
public class DefaultUserInitializer implements ApplicationRunner {

	private final AppUserRepository appUserRepository;
	private final PasswordEncoder passwordEncoder;
	private final FirstAccessCredential firstAccessCredential;
	private final String username;
	private final String configuredPassword;
	private final String resetPassword;

	public DefaultUserInitializer(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder,
			FirstAccessCredential firstAccessCredential, NimbusFileManagerProperties properties) {
		this.appUserRepository = appUserRepository;
		this.passwordEncoder = passwordEncoder;
		this.firstAccessCredential = firstAccessCredential;
		this.username = properties.security().defaultUsername();
		this.configuredPassword = properties.security().defaultPassword();
		this.resetPassword = properties.security().resetPassword();
	}

	@Override
	public void run(ApplicationArguments args) {
		if (appUserRepository.count() > 0) {
			applyPasswordReset();
			markConfiguredPasswordStillInUse();

			return;
		}

		boolean generated = configuredPassword == null || configuredPassword.isBlank();

		String password = generated ? firstAccessCredential.generate() : configuredPassword;

		appUserRepository.save(AppUser.builder().username(username).passwordHash(passwordEncoder.encode(password))
				.displayName("Administrator").role(Role.ADMIN).enabled(true).twoFactorEnabled(false)
				.passwordChangeRequired(true).build());

		if (generated) {
			firstAccessCredential.publish(username, password);
		} else {
			log.warn("Default application user created with the configured password. username={}. Change it "
					+ "immediately.", username);
		}
	}

	/**
	 * An installation seeded before this check, still signing in with the password
	 * from the configuration, is put back under the change requirement. Only
	 * possible while a password is configured: a generated one exists nowhere to be
	 * compared against, which is the whole point of generating it.
	 */
	private void markConfiguredPasswordStillInUse() {
		if (configuredPassword == null || configuredPassword.isBlank()) {
			return;
		}

		appUserRepository.findByUsernameIgnoreCase(username)
				.filter(user -> passwordEncoder.matches(configuredPassword, user.getPasswordHash()))
				.filter(user -> !Boolean.TRUE.equals(user.getPasswordChangeRequired())).ifPresent(user -> {
					user.setPasswordChangeRequired(true);

					appUserRepository.save(user);

					log.warn("Configured default password is still in use. Password change is required for "
							+ "username={}.", username);
				});
	}

	/**
	 * The way back in when nobody can sign in any more.
	 *
	 * <p>
	 * A restored backup brings the users of the installation it came from, so
	 * whoever restores their catalog onto a new machine is locked out by
	 * credentials they may never have known. This resets the administrator once
	 * and requires a change at the next sign-in.
	 *
	 * <p>
	 * Deliberately a property of its own rather than a new meaning for
	 * {@code default-password}: that one provisions an empty installation, and a
	 * container restarting with it set must never undo the password its owner has
	 * since chosen. It also grants nothing new - whoever can write the
	 * configuration of an installation can already read the database password
	 * sitting next to it. What it removes is the state whose only way out was
	 * editing rows by hand.
	 *
	 * <p>
	 * The lockout goes with the password: an account locked by the very attempts
	 * that led here would leave the operator waiting on a timer for a decision
	 * already taken.
	 */
	private void applyPasswordReset() {
		if (resetPassword == null || resetPassword.isBlank()) {
			return;
		}

		appUserRepository.findByUsernameIgnoreCase(username).ifPresent(user -> {
			user.setPasswordHash(passwordEncoder.encode(resetPassword));
			user.setPasswordChangeRequired(true);
			user.setFailedLoginAttempts(0);
			user.setLockedUntil(null);

			appUserRepository.save(user);

			log.warn("Password of username={} was reset from the configuration. Change it at the next sign-in, "
					+ "and clear nimbus-file-manager.security.reset-password afterwards.", username);
		});
	}
}