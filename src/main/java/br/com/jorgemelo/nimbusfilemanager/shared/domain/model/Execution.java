package br.com.jorgemelo.nimbusfilemanager.shared.domain.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.ClockHolder;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
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
@Table(name = "execution")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Execution {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	@Column(name = "public_id", nullable = false, unique = true, updatable = false)
	private UUID publicId;

	@Enumerated(EnumType.STRING)
	@Column(name = "execution_type", nullable = false, length = 30)
	private ExecutionType executionType;

	@Enumerated(EnumType.STRING)
	@Column(name = "trigger_event", length = 30)
	private ExecutionTrigger triggerEvent;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private ExecutionStatus status;

	@Column(name = "started_at", nullable = false)
	private LocalDateTime startedAt;

	@Column(name = "finished_at")
	private LocalDateTime finishedAt;

	@Column(name = "source_path")
	private String sourcePath;

	@Column(name = "target_path")
	private String targetPath;

	@Column(nullable = false)
	private Boolean recursive;

	@Column(name = "execute_flag", nullable = false)
	private Boolean executeFlag;

	@Column(name = "application_version", length = 50)
	private String applicationVersion;

	@Embedded
	private StatusMessage statusMessage;

	@Column(name = "files_found", nullable = false)
	private Integer filesFound;

	@Column(name = "files_analyzed", nullable = false)
	private Integer filesAnalyzed;

	@Column(name = "cache_hits", nullable = false)
	private Integer cacheHits;

	@Column(name = "files_moved", nullable = false)
	private Integer filesMoved;

	@Column(name = "simulated_files", nullable = false)
	private Integer simulatedFiles;

	@Column(name = "errors", nullable = false)
	private Integer errors;

	@Column(name = "total_expected")
	private Integer totalExpected;

	@OneToMany(mappedBy = "execution", cascade = CascadeType.ALL, orphanRemoval = true)
	@Builder.Default
	@ToString.Exclude
	private List<Movement> movements = new ArrayList<>();

	@PrePersist
	void prePersist() {
		if (publicId == null) {
			publicId = UuidV7.generate();
		}

		if (startedAt == null) {
			startedAt = LocalDateTime.now(ClockHolder.clock());
		}

		if (recursive == null) {
			recursive = false;
		}

		if (executeFlag == null) {
			executeFlag = false;
		}

		if (filesFound == null) {
			filesFound = 0;
		}

		if (filesAnalyzed == null) {
			filesAnalyzed = 0;
		}

		if (cacheHits == null) {
			cacheHits = 0;
		}

		if (filesMoved == null) {
			filesMoved = 0;
		}

		if (simulatedFiles == null) {
			simulatedFiles = 0;
		}

		if (errors == null) {
			errors = 0;
		}
	}
}