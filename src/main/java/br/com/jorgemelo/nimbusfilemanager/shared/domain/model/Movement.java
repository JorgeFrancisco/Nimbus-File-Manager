package br.com.jorgemelo.nimbusfilemanager.shared.domain.model;

import java.time.LocalDateTime;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.ClockHolder;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
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
@Table(name = "movement")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Movement {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	@Column(name = "public_id", nullable = false, unique = true, updatable = false)
	private UUID publicId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "execution_id", nullable = false)
	@ToString.Exclude
	private Execution execution;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "catalog_file_id")
	@ToString.Exclude
	private CatalogFile catalogFile;

	@Column(name = "source_path", nullable = false)
	private String sourcePath;

	@Column(name = "target_path", nullable = false)
	private String targetPath;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private MovementStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "reason", length = 30)
	private MovementReason reason;

	@Column(name = "moved_at", nullable = false)
	private LocalDateTime movedAt;

	@PrePersist
	void prePersist() {
		if (publicId == null) {
			publicId = UuidV7.generate();
		}

		if (movedAt == null) {
			movedAt = LocalDateTime.now(ClockHolder.clock());
		}
	}
}