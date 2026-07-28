package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model;

import java.time.LocalDateTime;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintFailureReason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Operational record for an item whose fingerprint could NOT be computed. Only
 * failures live here - a successful item has a {@code media_fingerprint} row
 * and none here. Its existence is what keeps an undecodable photo out of the
 * derived pending queue (so the screen unblocks) while still allowing a
 * bounded, manual retry (attempts are counted, never auto-looped).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "fingerprint_failure")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class FingerprintFailure {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	@Column(name = "catalog_file_id", nullable = false)
	private Long catalogFileId;

	@Enumerated(EnumType.STRING)
	@Column(name = "kind", nullable = false, length = 30)
	private FingerprintKind kind;

	@Column(name = "algorithm", nullable = false, length = 40)
	private String algorithm;

	@Column(name = "attempts", nullable = false)
	@Builder.Default
	private Integer attempts = 0;

	@Enumerated(EnumType.STRING)
	@Column(name = "reason", nullable = false, length = 30)
	@Builder.Default
	private FingerprintFailureReason reason = FingerprintFailureReason.UNKNOWN;

	@Column(name = "last_error")
	private String lastError;

	@Column(name = "last_attempt_at")
	private LocalDateTime lastAttemptAt;
}