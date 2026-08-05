package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model;

import java.time.LocalDateTime;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.GroupingStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.ClockHolder;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
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
 * One similarity analysis, as a result that outlives the run that produced it.
 *
 * <p>
 * The distinction that shapes this table: an {@code Execution} is the lifecycle
 * of an attempt - who asked for it, how far it got, whether it was claimed,
 * cancelled or crashed - and it is retained by the rules of the executions
 * screen. This is the answer. It stays useful long after that row is cleaned
 * up, which is why the execution is referenced with {@code ON DELETE SET NULL}
 * instead of owning it: forgetting who asked must never forget what was found.
 *
 * <p>
 * The four family columns decide which results compete for being current; the
 * composition columns say what this one is actually about. Keeping them apart is
 * what lets a published result stay on screen while the library moves on - the
 * family still matches, and the composition is how the screen knows a newer
 * analysis would see more.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "similarity_grouping")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SimilarityGrouping {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	@Column(name = "public_id", nullable = false, unique = true)
	private UUID publicId;

	@Enumerated(EnumType.STRING)
	@Column(name = "media_type", nullable = false, length = 20)
	private FileType mediaType;

	@Column(name = "algorithm_id", nullable = false, length = 100)
	private String algorithmId;

	@Column(name = "grouping_version", nullable = false)
	private Integer groupingVersion;

	@Column(name = "parameters_digest", nullable = false, length = 64)
	private String parametersDigest;

	@Column(name = "composition_digest", nullable = false, length = 64)
	private String compositionDigest;

	/** How many files satisfied every rule, before the candidate cap. */
	@Column(name = "eligible_count", nullable = false)
	private Integer eligibleCount;

	/** How many the cap let through - what the algorithm actually saw. */
	@Column(name = "analyzed_count", nullable = false)
	private Integer analyzedCount;

	@Column(name = "candidate_limit", nullable = false)
	private Integer candidateLimit;

	@Column(name = "selection_policy", nullable = false, length = 40)
	private String selectionPolicy;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private GroupingStatus status;

	@Column(name = "computed_at", nullable = false)
	private LocalDateTime computedAt;

	@Column(name = "published_at")
	private LocalDateTime publishedAt;

	@Column(name = "execution_id")
	private Long executionId;

	@Column(name = "group_count", nullable = false)
	private Integer groupCount;

	@Column(name = "member_count", nullable = false)
	private Integer memberCount;

	@PrePersist
	void beforePersist() {
		if (publicId == null) {
			publicId = UUID.randomUUID();
		}

		if (computedAt == null) {
			computedAt = LocalDateTime.now(ClockHolder.clock());
		}

		if (groupCount == null) {
			groupCount = 0;
		}

		if (memberCount == null) {
			memberCount = 0;
		}
	}

	/**
	 * Whether every eligible file made it in. Derived from the two counts rather
	 * than stored beside them, so it can never disagree with them.
	 */
	public boolean coverageComplete() {
		return analyzedCount >= eligibleCount;
	}
}