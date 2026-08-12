package br.com.jorgemelo.nimbusfilemanager.execution.domain.model;

import java.time.LocalDateTime;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.ClockHolder;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;
import jakarta.persistence.Column;
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
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "execution_error")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ExecutionError {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	@Column(name = "execution_error_public_id", nullable = false, unique = true, updatable = false)
	private UUID executionErrorPublicId;

	// Required: a failure with no execution answers nothing - not which operation,
	// not when, not over which folder - and the database deletes it with its
	// execution rather than keeping it as a row nobody can read.
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "execution_id", nullable = false)
	@ToString.Exclude
	private Execution execution;

	@Column(name = "path", nullable = false)
	private String path;

	@Enumerated(EnumType.STRING)
	@Column(name = "error_type", nullable = false, length = 30)
	private ExecutionErrorType errorType;

	@Column(name = "error_message", nullable = false)
	private String errorMessage;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@PrePersist
	void prePersist() {
		if (executionErrorPublicId == null) {
			executionErrorPublicId = UuidV7.generate();
		}

		if (createdAt == null) {
			createdAt = LocalDateTime.now(ClockHolder.clock());
		}

		if (errorType == null) {
			errorType = ExecutionErrorType.UNKNOWN;
		}
	}
}