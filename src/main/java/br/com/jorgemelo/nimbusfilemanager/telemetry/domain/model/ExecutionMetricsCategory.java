package br.com.jorgemelo.nimbusfilemanager.telemetry.domain.model;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExternalToolCategory;
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
 * What one execution spent on one kind of external tool.
 *
 * <p>
 * Separate from the aggregate because the numbers are not comparable across
 * categories and folding them together loses the only question worth asking of
 * them: a photo hash and a video probe have different limits, run on different
 * semaphores and cost different amounts, so "ffmpeg took an hour" is an answer
 * that names no tool. One row per category the run actually used.
 *
 * <p>
 * The category is the {@link ExternalToolCategory} itself rather than a label,
 * so a rename of the text a screen shows can never silently split one category
 * into two in the history.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "execution_metrics_category")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ExecutionMetricsCategory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	@Column(name = "execution_id", nullable = false)
	private Long executionId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private ExternalToolCategory category;

	@Column(nullable = false)
	private Long runs;

	@Column(name = "gate_wait_millis", nullable = false)
	private Long gateWaitMillis;

	@Column(name = "external_exec_millis", nullable = false)
	private Long externalExecMillis;
}