package br.com.jorgemelo.nimbusfilemanager.shared.domain.model;

import java.time.Instant;
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

/**
 * One operation the Nimbus issued over one catalogued file.
 *
 * <p>
 * It exists before the file system is touched, which is the whole difference
 * from what this class used to be. A row written afterwards is a receipt: it can
 * say what happened but not what was being attempted, so a worker that died
 * mid-operation left nothing for its replacement to recognise, and a retry could
 * only mint fresh identities - making a repeat of one job indistinguishable from
 * a second job.
 *
 * <p>
 * Only for operations this product issues. A file the watcher saw move is not an
 * operation and gets no movement: nobody ordered it, and inventing a command for
 * it would put the catalog's own name on somebody else's action.
 */
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

	/** Identity of the operation, which is not the identity of the run. */
	@Column(name = "movement_public_id", nullable = false, unique = true, updatable = false)
	private UUID movementPublicId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "execution_id", nullable = false)
	@ToString.Exclude
	private Execution execution;

	/**
	 * The file this moved, which a movement never exists without. It used to
	 * detach when the file was destroyed for good, leaving a row describing paths
	 * that lead nowhere; now it goes with it. The execution stays - it aggregates
	 * many files and keeps its own totals.
	 */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "catalog_file_id", nullable = false)
	@ToString.Exclude
	private CatalogFile catalogFile;

	/**
	 * The identity the fact will carry if this operation produces one.
	 *
	 * <p>
	 * Reserved at preparation and never changed after, which is what lets a retry
	 * record the same fact instead of a second one. While the operation is pending
	 * no such fact exists yet, and for one that was skipped or failed none ever
	 * will - the identity is simply never consumed. Deliberately not a foreign key:
	 * a column that can only be filled after the fact would be a second answer to
	 * "did this happen", and the status is already the first.
	 */
	@Column(name = "catalog_file_event_public_id", nullable = false, unique = true, updatable = false)
	private UUID catalogFileEventPublicId;

	/** Where the caller asked the file to come from - a request, not a record. */
	@Column(name = "requested_source_path", nullable = false)
	private String requestedSourcePath;

	@Column(name = "requested_target_path", nullable = false)
	private String requestedTargetPath;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private MovementStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "reason", length = 30)
	private MovementReason reason;

	/** When the operation was persisted, always before anything was touched. */
	@Column(name = "prepared_at", nullable = false, updatable = false)
	private Instant preparedAt;

	/**
	 * When the file actually moved - null until it does, and null forever for an
	 * operation that was skipped or failed.
	 */
	@Column(name = "moved_at")
	private Instant movedAt;

	@PrePersist
	void prePersist() {
		if (movementPublicId == null) {
			movementPublicId = UuidV7.generate();
		}

		if (catalogFileEventPublicId == null) {
			catalogFileEventPublicId = UuidV7.generate();
		}

		if (preparedAt == null) {
			preparedAt = Instant.now(ClockHolder.clock());
		}

		if (status == null) {
			status = MovementStatus.PENDING;
		}
	}
}