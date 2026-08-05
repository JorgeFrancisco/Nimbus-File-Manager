package br.com.jorgemelo.nimbusfilemanager.organization.domain.model;

import java.time.LocalDateTime;

import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.PlanStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One organization preview, as something that outlives the process that
 * produced it.
 *
 * <p>
 * The primary key is the execution's own id, which states the relationship
 * rather than describing it: a plan belongs to exactly one execution and cannot
 * exist without it. The screen always asks for the plan <em>of an execution</em>
 * - never for "the latest plan" - so there is no competition between plans and
 * nothing to arbitrate.
 *
 * <p>
 * What was asked for is copied here instead of being read back from the
 * execution. The execution is history and keeps the executions screen's
 * retention; the plan is an artifact to look at and expires much sooner, so for
 * the window in which both exist the copy costs three columns, and outside it
 * the plan is still explainable.
 *
 * <p>
 * The name avoids {@code OrganizationPlan}, which is the DTO the planner
 * produces and the API returns. They are different things: one is the shape of
 * an answer, this is a row.
 */
@Entity
@Table(name = "organization_plan")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "executionId")
public class OrganizationPlanRecord {

	@Id
	@Column(name = "execution_id", nullable = false)
	private Long executionId;

	@Column(name = "source_path", nullable = false, length = 1024)
	private String sourcePath;

	@Column(name = "target_path", nullable = false, length = 1024)
	private String targetPath;

	@Enumerated(EnumType.STRING)
	@Column(name = "layout", nullable = false, length = 40)
	private OrganizationLayout layout;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 12)
	private PlanStatus status;

	@Column(name = "item_count", nullable = false)
	private Integer itemCount;

	@Column(name = "conflict_count", nullable = false)
	private Integer conflictCount;

	@Column(name = "planned_moves", nullable = false)
	private Integer plannedMoves;

	@Column(name = "total_size_bytes", nullable = false)
	private Long totalSizeBytes;

	/**
	 * The catalog as it was when this plan was produced. The execute recalculates
	 * and deliberately does not read the plan, so what the user saw and what a run
	 * would do can differ - this is what lets the screen say so.
	 */
	@Column(name = "catalog_signature", length = 120)
	private String catalogSignature;

	@Column(name = "built_at")
	private LocalDateTime builtAt;

	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;
}