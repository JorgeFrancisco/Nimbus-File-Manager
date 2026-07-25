package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model;

import java.time.LocalDateTime;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.ClockHolder;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One visual fingerprint sample (results only, no operational state). Linked to
 * {@code catalog_file} by id - not a managed relationship - so writing one never
 * loads the parent graph. A photo has a single row (sample_index 0); a video
 * will have several. Rows of different {@code algorithm} are never compared to
 * each other, so evolving the hash implementation is just a new algorithm
 * string.
 *
 * <p>
 * Because it is not a JPA relationship, cleanup on media deletion is done by
 * the database: the FK {@code fk_media_fingerprint_file} is
 * {@code ON DELETE CASCADE} (same for {@code fingerprint_failure}), so deleting
 * a {@code catalog_file} removes its fingerprints/failures - no orphans (proven
 * by FingerprintPersistenceIntegrationTest).
 *
 * <p>
 * Storage of future descriptors: different algorithms may coexist without
 * sharing the same physical representation.
 * <ul>
 * <li><b>Compact bit-hash (&le;64 bits)</b> - dHash/pHash-64: stays in
 * {@link #hash} (BIGINT), keeping the cheap XOR/Hamming path.</li>
 * <li><b>Wide bit-hash (&gt;64 bits)</b> - pHash-256 uses {@link #hashBytes};
 * its normalized luminance payload for SSIM uses {@link #sampleBytes}.</li>
 * <li><b>AI / face embeddings</b> (float vectors, cosine/L2) do NOT belong here
 * - they go to a separate {@code media_embedding} structure, where pgvector vs
 * BYTEA is decided then (checking the offline test Postgres supports it).</li>
 * </ul>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "media_fingerprint")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class MediaFingerprint {

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

	@Column(name = "sample_index", nullable = false)
	@Builder.Default
	private Integer sampleIndex = 0;

	@Column(name = "position_ms")
	private Long positionMs;

	@Column(name = "hash")
	private Long hash;

	@Column(name = "hash_bytes")
	private byte[] hashBytes;

	@Column(name = "sample_bytes")
	private byte[] sampleBytes;

	@Column(name = "computed_at", nullable = false)
	private LocalDateTime computedAt;

	@PrePersist
	void prePersist() {
		if (sampleIndex == null) {
			sampleIndex = 0;
		}

		if (computedAt == null) {
			computedAt = LocalDateTime.now(ClockHolder.clock());
		}
	}
}