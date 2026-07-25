package br.com.jorgemelo.nimbusfilemanager.security.application;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.security.domain.enums.Role;
import br.com.jorgemelo.nimbusfilemanager.security.domain.model.AppUser;
import br.com.jorgemelo.nimbusfilemanager.security.domain.repository.AppUserRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

@Service
public class AppUserAccountService extends LocalizedComponent {

	private static final String ADMIN = "ADMIN";
	private static final String USER = "USER";
	private static final int CONFIRMATION_TOKEN_VALID_HOURS = 24;

	private final AppUserRepository appUserRepository;
	private final PasswordEncoder passwordEncoder;
	private final AccountLockService accountLockService;
	private final Clock clock;

	public AppUserAccountService(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder,
			AccountLockService accountLockService, Clock clock) {
		this.appUserRepository = appUserRepository;
		this.passwordEncoder = passwordEncoder;
		this.accountLockService = accountLockService;
		this.clock = clock;
	}

	/**
	 * Self-service sign-up: the account is created disabled, with a confirmation
	 * token/expiry, so it can't log in until {@link #confirmRegistration(String)}
	 * is called with a matching, unexpired token. Contrast with
	 * {@link #createUser(String, String, String, String)}, used for admin-created
	 * accounts, which are enabled immediately - and with the seeded default admin
	 * user (created directly by DefaultUserInitializer), which never goes through
	 * this path at all and so is unaffected by the confirmation requirement.
	 * <p>
	 * Registering again with an email that already belongs to a still-unconfirmed
	 * account is treated as "send me a new link" rather than rejected: it refreshes
	 * the password, display name and confirmation token/expiry on that same row.
	 * Without this, a token that expires after
	 * {@link #CONFIRMATION_TOKEN_VALID_HOURS} hours would leave the account
	 * permanently stuck - confirming fails (token expired) and re-registering would
	 * otherwise fail too ("E-mail já cadastrado."), with no self-service way out.
	 * Only an already-confirmed (enabled) account still rejects a duplicate email.
	 */
	@Transactional
	public AppUser register(String email, String displayName, String password) {
		String normalizedEmail = normalizeEmail(email);

		Optional<AppUser> existing = appUserRepository.findByUsernameIgnoreCase(normalizedEmail);

		if (existing.isPresent() && Boolean.TRUE.equals(existing.get().getEnabled())) {
			throw new IllegalArgumentException(message("backend.account.emailRegistered"));
		}

		validatePassword(password);

		AppUser user = existing.orElseGet(
				() -> AppUser.builder().username(normalizedEmail).role(Role.USER).twoFactorEnabled(false).build());

		user.setDisplayName(normalizeDisplayName(displayName, normalizedEmail));
		user.setPasswordHash(passwordEncoder.encode(password));
		user.setEnabled(false);
		user.setConfirmationToken(UUID.randomUUID().toString());
		user.setConfirmationTokenExpiresAt(LocalDateTime.now(clock).plusHours(CONFIRMATION_TOKEN_VALID_HOURS));

		return appUserRepository.save(user);
	}

	@Transactional
	public AppUser confirmRegistration(String token) {
		if (token == null || token.isBlank()) {
			throw new IllegalArgumentException(message("backend.account.tokenInvalid"));
		}

		AppUser user = appUserRepository.findByConfirmationToken(token)
				.orElseThrow(() -> new IllegalArgumentException(message("backend.account.tokenInvalid")));

		if (user.getConfirmationTokenExpiresAt() == null
				|| user.getConfirmationTokenExpiresAt().isBefore(LocalDateTime.now(clock))) {
			throw new IllegalArgumentException(message("backend.account.tokenExpired"));
		}

		user.setEnabled(true);
		user.setConfirmationToken(null);
		user.setConfirmationTokenExpiresAt(null);

		return user;
	}

	@Transactional
	public AppUser createUser(String email, String displayName, String password, String role) {
		String normalizedEmail = normalizeEmail(email);

		String normalizedName = normalizeDisplayName(displayName, normalizedEmail);

		Role normalizedRole = normalizeRole(role);

		validatePassword(password);

		if (appUserRepository.existsByUsernameIgnoreCase(normalizedEmail)) {
			throw new IllegalArgumentException(message("backend.account.emailRegistered"));
		}

		return appUserRepository.save(AppUser.builder().username(normalizedEmail)
				.passwordHash(passwordEncoder.encode(password)).displayName(normalizedName).role(normalizedRole)
				.enabled(true).twoFactorEnabled(false).build());
	}

	@Transactional(readOnly = true)
	public Page<AppUser> searchUsers(String query, int page, int size) {
		int normalizedPage = Math.max(page, 0);

		int normalizedSize = normalizePageSize(size);

		PageRequest pageRequest = PageRequest.of(normalizedPage, normalizedSize,
				Sort.by(Sort.Order.asc("username").ignoreCase()));

		String normalizedQuery = query == null ? "" : query.trim();

		if (normalizedQuery.isBlank()) {
			return appUserRepository.findAll(pageRequest);
		}

		return appUserRepository.findByUsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(normalizedQuery,
				normalizedQuery, pageRequest);
	}

	/**
	 * {@code /change-password} is reachable without a session (it's how a user with
	 * a forgotten password recovers, and how the "default password still in use"
	 * prompt is resolved), which makes {@code currentPassword} an unauthenticated
	 * password-guessing oracle just like the login form. It's gated by the same
	 * {@link AccountLockService} used for form login and {@code /login/2fa}, on the
	 * same shared per-account counter, so this can't be used to keep guessing after
	 * the account is locked out through the login page (or vice-versa).
	 */
	@Transactional
	public void changePassword(String email, String currentPassword, String newPassword) {
		String normalizedEmail = normalizeEmail(email);

		AppUser user = appUserRepository.findByUsernameIgnoreCase(normalizedEmail)
				.orElseThrow(() -> new IllegalArgumentException(message("backend.account.userNotFound")));

		if (user.isCurrentlyLocked()) {
			throw new IllegalArgumentException(message("backend.account.accountLocked"));
		}

		if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
			accountLockService.registerFailure(normalizedEmail, null, null);

			throw new IllegalArgumentException(message("backend.account.currentPasswordInvalid"));
		}

		if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
			throw new IllegalArgumentException(message("backend.account.passwordSameAsCurrent"));
		}

		validatePassword(newPassword);

		user.setPasswordHash(passwordEncoder.encode(newPassword));
		user.setPasswordChangeRequired(false);

		accountLockService.registerSuccess(normalizedEmail);
	}

	/**
	 * Sets a new password for a user who is <b>required</b> to change it (the
	 * seeded default), without asking for the current one: they already
	 * authenticated with it to reach the account screen, so re-typing it is
	 * redundant - and it collides with the browser password manager on the forced
	 * first-login form ("senha atual inválida"). Guarded to only act while
	 * {@code passwordChangeRequired} is set, so it can never bypass the
	 * current-password check for a normal account.
	 */
	@Transactional
	public void resetRequiredPassword(String email, String newPassword) {
		String normalizedEmail = normalizeEmail(email);

		AppUser user = appUserRepository.findByUsernameIgnoreCase(normalizedEmail)
				.orElseThrow(() -> new IllegalArgumentException(message("backend.account.userNotFound")));

		if (!Boolean.TRUE.equals(user.getPasswordChangeRequired())) {
			throw new IllegalArgumentException(message("backend.account.passwordChangeNotRequired"));
		}

		if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
			throw new IllegalArgumentException(message("backend.account.passwordMustDiffer"));
		}

		validatePassword(newPassword);

		user.setPasswordHash(passwordEncoder.encode(newPassword));
		user.setPasswordChangeRequired(false);
	}

	@Transactional
	public AppUser upsertOAuthUser(String email, String displayName) {
		String normalizedEmail = normalizeEmail(email);

		String normalizedName = normalizeDisplayName(displayName, normalizedEmail);

		return appUserRepository.findByUsernameIgnoreCase(normalizedEmail).map(user -> {
			user.setDisplayName(normalizedName);
			user.setEnabled(true);

			return user;
		}).orElseGet(() -> appUserRepository.save(AppUser.builder().username(normalizedEmail)
				.passwordHash(passwordEncoder.encode(UUID.randomUUID().toString())).displayName(normalizedName)
				.role(Role.USER).enabled(true).twoFactorEnabled(false).build()));
	}

	private String normalizeEmail(String email) {
		if (email == null || email.isBlank()) {
			throw new IllegalArgumentException(message("backend.account.emailRequired"));
		}

		String normalized = email.trim().toLowerCase(Locale.ROOT);

		if (!normalized.contains("@")) {
			throw new IllegalArgumentException(message("backend.account.emailInvalid"));
		}

		return normalized;
	}

	private String normalizeDisplayName(String displayName, String email) {
		return displayName == null || displayName.isBlank() ? email : displayName.trim();
	}

	private void validatePassword(String password) {
		if (password == null || password.length() < 6) {
			throw new IllegalArgumentException(message("backend.account.passwordTooShort"));
		}
	}

	private Role normalizeRole(String role) {
		String normalized = role == null ? USER : role.trim().toUpperCase(Locale.ROOT);

		return ADMIN.equals(normalized) ? Role.ADMIN : Role.USER;
	}

	private int normalizePageSize(int size) {
		return size == 50 || size == 100 ? size : 20;
	}
}