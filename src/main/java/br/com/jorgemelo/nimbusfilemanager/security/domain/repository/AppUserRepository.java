package br.com.jorgemelo.nimbusfilemanager.security.domain.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.jorgemelo.nimbusfilemanager.security.domain.model.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

	Optional<AppUser> findByUsername(String username);

	Optional<AppUser> findByUsernameIgnoreCase(String username);

	boolean existsByUsernameIgnoreCase(String username);

	Optional<AppUser> findByConfirmationToken(String confirmationToken);

	Page<AppUser> findByUsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(String username,
			String displayName, Pageable pageable);

	/**
	 * Atomically increments the failed-login counter, never losing a concurrent
	 * attempt, and only while the account is not already locked. Bumps
	 * {@code version} so any entity already loaded elsewhere becomes stale.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
			update AppUser u
			   set u.failedLoginAttempts = u.failedLoginAttempts + 1,
			       u.version = u.version + 1,
			       u.updatedAt = :now
			 where u.id = :id
			   and (u.lockedUntil is null or u.lockedUntil <= :now)
			""")
	int incrementFailedAttempts(@Param("id") Long id, @Param("now") LocalDateTime now);

	/**
	 * Applies the lockout in a single atomic step, but only for the thread whose
	 * increment reached the threshold (the {@code >= :maxAttempts} guard means the
	 * loser's row no longer matches after the winner reset it to 0). Returns 1 when
	 * the lock was applied - the caller logs the ACCOUNT_LOCKED audit entry then.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
			update AppUser u
			   set u.failedLoginAttempts = 0,
			       u.lockedUntil = :lockedUntil,
			       u.version = u.version + 1,
			       u.updatedAt = :now
			 where u.id = :id
			   and u.failedLoginAttempts >= :maxAttempts
			   and (u.lockedUntil is null or u.lockedUntil <= :now)
			""")
	int applyLockoutIfThresholdReached(@Param("id") Long id, @Param("maxAttempts") int maxAttempts,
			@Param("lockedUntil") LocalDateTime lockedUntil, @Param("now") LocalDateTime now);

	/**
	 * Clears failures/lock on a successful authentication, atomically and only when
	 * there is something to clear (so a success never writes needlessly).
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
			update AppUser u
			   set u.failedLoginAttempts = 0,
			       u.lockedUntil = null,
			       u.version = u.version + 1,
			       u.updatedAt = :now
			 where u.id = :id
			   and (u.failedLoginAttempts > 0 or u.lockedUntil is not null)
			""")
	int clearFailuresOnSuccess(@Param("id") Long id, @Param("now") LocalDateTime now);
}