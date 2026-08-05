package br.com.jorgemelo.nimbusfilemanager.organization.domain.model;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One line of a stored plan, in the order the planner decided.
 *
 * <p>
 * {@code ordinal} is half the primary key rather than a surrogate id because the
 * pagination is a scan over exactly that: page two means the same rows every
 * time it is asked for, which a plan of a hundred thousand items has no other
 * way to promise.
 *
 * <p>
 * Only what the screen renders is stored. The eleven other fields the planner's
 * item carries are working state of the planning itself, and the execute
 * recalculates rather than reading this - so persisting them would be keeping a
 * copy nobody reads and nobody can trust.
 */
@Entity
@Table(name = "organization_plan_item")
@IdClass(OrganizationPlanItemId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = { "executionId", "ordinal" })
public class OrganizationPlanItemRecord implements Serializable {

	private static final long serialVersionUID = 1L;

	@Id
	@Column(name = "execution_id", nullable = false)
	private Long executionId;

	@Id
	@Column(name = "ordinal", nullable = false)
	private Integer ordinal;

	@Column(name = "catalog_file_id", nullable = false)
	private UUID catalogFileId;

	@Column(name = "file_name", nullable = false, length = 512)
	private String fileName;

	@Column(name = "source_path", nullable = false, length = 1024)
	private String sourcePath;

	@Column(name = "target_path", nullable = false, length = 1024)
	private String targetPath;

	@Column(name = "size_bytes")
	private Long sizeBytes;

	@Column(name = "location", length = 255)
	private String location;

	@Column(name = "location_confidence", length = 40)
	private String locationConfidence;

	@Column(name = "conflict", nullable = false)
	private boolean conflict;

	@Column(name = "conflict_type", length = 40)
	private String conflictType;
}