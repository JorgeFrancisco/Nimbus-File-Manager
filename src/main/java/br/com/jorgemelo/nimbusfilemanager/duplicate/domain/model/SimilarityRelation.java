package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One pair the analysis approved, kept so that a change to the library costs
 * what the change is worth instead of what the library is worth.
 *
 * <p>
 * Removing a file is the case this exists for. The grouping is greedy, so a
 * member can be the reason another candidate was refused, and dropping it
 * locally would miss the group the two of them would have formed - a full
 * rebuild is the only correct answer, and that is 37 seconds. The relations,
 * though, do not change when a file goes away: re-running the grouping over the
 * survivors gives the rebuild's answer in about fifty milliseconds.
 *
 * <p>
 * Keyed by what decides whether two files relate - the fingerprint algorithm,
 * the distance allowed, the similarity required, and a digest of whatever else
 * the medium's comparison depends on - and not by the grouping's
 * {@code parametersDigest}, which also carries the exclusions and the selection
 * policy. Those decide which files enter an analysis, not whether two of them
 * look alike, so a folder exclusion must not throw away facts that did not
 * change.
 *
 * <p>
 * The ids are ordered by the database ({@code first < second}), which makes the
 * reversed spelling and the self-relation impossible rather than merely
 * discouraged. They are catalog ids because the grouping visits candidates in
 * {@code catalog_file.id} order: the stored pair already reads in analysis
 * order.
 */
@Entity
@Table(name = "similarity_relation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SimilarityRelation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "algorithm_id", nullable = false, length = 64)
	private String algorithmId;

	@Column(name = "max_distance", nullable = false)
	private Integer maxDistance;

	@Column(name = "min_similarity", nullable = false)
	private Integer minSimilarity;

	/**
	 * Whatever else the medium's comparison depends on, as a digest: empty for
	 * photos, and the video quorum, trim and tolerances for videos. Keeping it out
	 * of the three columns above is what stops the shared table from growing a
	 * column per medium.
	 */
	@Column(name = "relation_digest", nullable = false, length = 64)
	private String relationDigest;

	@Column(name = "first_catalog_file_id", nullable = false)
	private Long firstCatalogFileId;

	@Column(name = "second_catalog_file_id", nullable = false)
	private Long secondCatalogFileId;

	/**
	 * The SSIM the pair scored, stored because it is read again: a group's floor is
	 * the worst pair in it, and that percentage reaches the screen. It can change
	 * for an unchanged key only when a file is fingerprinted again - an edited
	 * file keeps its catalog id and gets a new fingerprint - which is why writing is an
	 * upsert rather than an insert.
	 */
	@Column(name = "similarity_percent", nullable = false)
	private Integer similarityPercent;

	@Column(name = "computed_at", nullable = false)
	private LocalDateTime computedAt;
}