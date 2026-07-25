package br.com.jorgemelo.nimbusfilemanager.preferences.domain.model;

import java.time.LocalDateTime;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.ClockHolder;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
@Table(name = "user_page_preference", uniqueConstraints = @UniqueConstraint(name = "uk_user_page_preference", columnNames = {
		"user_id", "page_key", "preference_key" }))
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class UserPagePreference {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	/**
	 * Owner: live data that belongs to a user, referenced by the
	 * stable app_user id (FK in the database, ON DELETE CASCADE) instead of the
	 * former username snapshot - so renaming a user never orphans their prefs.
	 */
	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "page_key", nullable = false, length = 80)
	private String pageKey;

	@Column(name = "preference_key", nullable = false, length = 80)
	private String preferenceKey;

	@Column(name = "preference_value")
	private String preferenceValue;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@PrePersist
	void prePersist() {
		LocalDateTime now = LocalDateTime.now(ClockHolder.clock());

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
}