package br.com.jorgemelo.nimbusfilemanager.metadata.domain.model;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What a dry run of the metadata rebuild found, stored where the screen can read
 * it after the process that computed it is gone.
 *
 * <p>
 * There is no two-phase publication here, unlike the organization plan: the
 * reading only ever asks for the preview of a <em>finished</em> execution, so
 * the execution's own status is the flag that makes these rows visible. A run
 * that dies halfway leaves rows nobody asks for, and its retry replaces them.
 *
 * <p>
 * Keyed by the execution rather than by a surrogate id because a run has exactly
 * one preview, and the question the screen asks is always "the preview of that
 * run".
 */
@Entity
@Table(name = "metadata_rebuild_preview")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "executionId")
public class MetadataRebuildPreviewRecord implements Serializable {

	private static final long serialVersionUID = 1L;

	@Id
	@Column(name = "execution_id", nullable = false)
	private Long executionId;

	@Column(name = "source_path", nullable = false, length = 1024)
	private String sourcePath;

	@Column(name = "candidates", nullable = false)
	private Integer candidates;

	/** Files of the folder the "continue where it stopped" cutoff leaves out. */
	@Column(name = "skipped_by_cutoff", nullable = false)
	private Integer skippedByCutoff;

	@Column(name = "examined", nullable = false)
	private Integer examined;

	@Column(name = "would_change", nullable = false)
	private Integer wouldChange;
}