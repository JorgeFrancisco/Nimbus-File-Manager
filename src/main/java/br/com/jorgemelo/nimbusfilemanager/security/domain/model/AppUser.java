package br.com.jorgemelo.nimbusfilemanager.security.domain.model;

import java.time.LocalDateTime;

import br.com.jorgemelo.nimbusfilemanager.security.domain.enums.Role;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.ClockHolder;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "app_user")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AppUser {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	/**
	 * Optimistic-lock version for concurrent read-modify-write of mutable fields
	 * (password, 2FA, admin edits). The security counter
	 * {@link #failedLoginAttempts} is instead updated atomically in the database
	 * (see {@code AppUserRepository}), which also bumps this column.
	 */
	@Version
	@Column(name = "version", nullable = false)
	private Long version;

	@Column(nullable = false, unique = true, length = 100)
	private String username;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(name = "display_name", nullable = false, length = 150)
	private String displayName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private Role role;

	@Column(nullable = false)
	private Boolean enabled;

	@Column(name = "two_factor_enabled", nullable = false)
	private Boolean twoFactorEnabled;

	@Column(name = "two_factor_secret", length = 64)
	private String twoFactorSecret;

	@Column(name = "password_change_required", nullable = false)
	private Boolean passwordChangeRequired;

	@Column(name = "failed_login_attempts", nullable = false)
	private Integer failedLoginAttempts;

	@Column(name = "locked_until")
	private LocalDateTime lockedUntil;

	@Column(name = "confirmation_token", length = 64)
	private String confirmationToken;

	@Column(name = "confirmation_token_expires_at")
	private LocalDateTime confirmationTokenExpiresAt;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@PrePersist
	void prePersist() {
		LocalDateTime now = LocalDateTime.now(ClockHolder.clock());

		if (enabled == null) {
			enabled = true;
		}

		if (twoFactorEnabled == null) {
			twoFactorEnabled = false;
		}

		if (passwordChangeRequired == null) {
			passwordChangeRequired = false;
		}

		if (failedLoginAttempts == null) {
			failedLoginAttempts = 0;
		}

		if (createdAt == null) {
			createdAt = now;
		}

		if (updatedAt == null) {
			updatedAt = now;
		}
	}

	@PreUpdate
	void preUpdate() {
		updatedAt = LocalDateTime.now(ClockHolder.clock());
	}

	/**
	 * True while {@link #lockedUntil} is set and still in the future. Shared by
	 * every surface that verifies a password/TOTP code against this account (form
	 * login via {@code AppUserDetailsService}, {@code /login/2fa},
	 * {@code /change-password}), so repeated brute-force attempts on any one of
	 * them lock the account for all of them.
	 */
	public boolean isCurrentlyLocked() {
		return lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now(ClockHolder.clock()));
	}
}