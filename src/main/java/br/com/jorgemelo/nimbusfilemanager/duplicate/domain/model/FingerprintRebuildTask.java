package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model;

import java.io.Serializable;
import java.time.LocalDateTime;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One file a rebuild still owes.
 *
 * <p>
 * The row exists so that "what is left to do" stops being read from the absence
 * of a fingerprint. While a rebuild is open, the fingerprint of a file it has
 * not reached yet is still the published answer for that file - it is only
 * replaced when its own replacement is ready, in the transaction that also
 * removes this row.
 *
 * <p>
 * Keyed by {@code (kind, algorithm)} rather than by the execution that asked.
 * An execution ends for reasons that say nothing about the work being finished -
 * a lease that lapsed as many times as the claim budget allows ends the row for
 * good, and a waiting duplicate supersedes it - and a work list tied to one
 * would be orphaned by any of them. Whichever execution holds the row next
 * adopts what is still here. It says what is owed; the taking says who may
 * deliver it.
 */
@Entity
@Table(name = "fingerprint_rebuild_task")
@IdClass(FingerprintRebuildTaskId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = { "kind", "algorithm", "catalogFileId" })
public class FingerprintRebuildTask implements Serializable {

	private static final long serialVersionUID = 1L;

	@Id
	@Enumerated(EnumType.STRING)
	@Column(name = "kind", nullable = false, length = 30)
	private FingerprintKind kind;

	@Id
	@Column(name = "algorithm", nullable = false, length = 40)
	private String algorithm;

	@Id
	@Column(name = "catalog_file_id", nullable = false)
	private Long catalogFileId;

	@Column(name = "seeded_at", nullable = false)
	private LocalDateTime seededAt;
}