package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One cluster of visually related files inside a published analysis.
 *
 * <p>
 * The percentage is stored as it was computed, not recomputed on read: it comes
 * from comparing fingerprints that may be gone by the time somebody opens the
 * screen, and a badge that changes because an unrelated file was deleted would
 * be describing a different analysis than the one it belongs to.
 *
 * <p>
 * {@code position} freezes the order the analysis decided - largest recoverable
 * waste first - so page two means the same thing on every request. Sorting at
 * read time by a value that ties would let two requests disagree about which
 * group sits on the boundary.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "similarity_group")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SimilarityGroup {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	@Column(name = "grouping_id", nullable = false)
	private Long groupingId;

	@Column(name = "similarity_percent", nullable = false)
	private Integer similarityPercent;

	@Column(name = "file_count", nullable = false)
	private Integer fileCount;

	@Column(name = "wasted_bytes", nullable = false)
	private Long wastedBytes;

	@Column(name = "position", nullable = false)
	private Integer position;
}