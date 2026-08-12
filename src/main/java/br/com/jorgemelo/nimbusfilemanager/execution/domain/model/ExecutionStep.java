package br.com.jorgemelo.nimbusfilemanager.execution.domain.model;

import java.time.LocalDateTime;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.ClockHolder;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "execution_step")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionStep {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "execution_step_public_id", nullable = false, unique = true, updatable = false)
	private UUID executionStepPublicId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "execution_id", nullable = false)
	private Execution execution;

	@Enumerated(EnumType.STRING)
	@Column(name = "step_type", nullable = false, length = 30)
	private ExecutionStepType stepType;

	@Column(name = "path")
	private String path;

	@Embedded
	private StatusMessage statusMessage;

	@Column(name = "files_found")
	private Integer filesFound;

	@Column(name = "files_analyzed")
	private Integer filesAnalyzed;

	@Column(name = "cache_hits")
	private Integer cacheHits;

	@Column(name = "errors")
	private Integer errors;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@PrePersist
	void prePersist() {
		if (executionStepPublicId == null) {
			executionStepPublicId = UuidV7.generate();
		}

		if (createdAt == null) {
			createdAt = LocalDateTime.now(ClockHolder.clock());
		}
	}
}