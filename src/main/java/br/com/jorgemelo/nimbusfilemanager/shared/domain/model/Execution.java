package br.com.jorgemelo.nimbusfilemanager.shared.domain.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.ClockHolder;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhase;
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

	@Enumerated(EnumType.STRING)
	@Column(length = 30)
	private ExecutionPhase phase;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	/**
	 * When a worker actually took the row. Null while it waits: an execution
	 * nobody has claimed has not started, and stamping it at enqueue time would
	 * make every duration on the history screen include the queue.
	 */
	@Column(name = "started_at")
	private LocalDateTime startedAt;

	@Column(name = "finished_at")
	private LocalDateTime finishedAt;

	@Column(name = "claimed_by", length = 100)
	private String claimedBy;

	@Column(name = "claimed_at")
	private LocalDateTime claimedAt;

	/**
	 * How long this worker's ownership is good for. Renewed while the work runs;
	 * it says who owns the row, never how long the work may take.
	 */
	@Column(name = "lease_until")
	private LocalDateTime leaseUntil;

	@Column(nullable = false)
	private Integer priority;

	/**
	 * How many attempts got as far as holding their locks and starting for real.
	 * Monotonic on purpose - a counter that can go back down stops meaning what
	 * its name says, and the only thing it is asked is whether a job keeps
	 * killing the worker that takes it.
	 */
	@Column(name = "claim_count", nullable = false)
	private Integer claimCount;

	@Column(name = "cancel_requested", nullable = false)
	private Boolean cancelRequested;

	@Column(name = "available_at", nullable = false)
	private LocalDateTime availableAt;

	/**
	 * What makes two requests the same request, for the types where a second one
	 * would be pointless. Null opts out of deduplication entirely, which is how
	 * the types that accept repeated requests share one pair of partial indexes
	 * with the types that do not.
	 */
	@Column(name = "dedup_key", length = 1024)
	private String dedupKey;

	/**
	 * The arguments this execution was requested with, for the types whose
	 * arguments are not already columns here. Written by the process that asks
	 * and read by the process that runs, possibly after an upgrade in between -
	 * so it is a versioned contract, not a scratch DTO.
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "request_payload", columnDefinition = "jsonb")
	private String requestPayload;

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

	/**
	 * How many catalog entries this execution actually corrected.
	 *
	 * <p>
	 * Reconcile's own measure of having done something: a rename followed, a stale
	 * path synced, a missing file marked. Counted as distinct items rather than as
	 * a sum of those three, because one entry can be both renamed and repaired in
	 * the same pass and would otherwise be counted twice.
	 *
	 * <p>
	 * Not {@code filesMoved}: that one means the application moved files on disk,
	 * and a reconcile moves nothing - it records moves made outside it.
	 */
	@Column(name = "repaired_items", nullable = false)
	private Integer repairedItems;

	/**
	 * How far into the item being worked on right now, from 0 to 100.
	 *
	 * <p>
	 * The counters above answer how many items are finished, which says nothing
	 * while a single one runs for hours. This answers the other half, and it is on
	 * the row for the same reason everything else is: the process doing the work
	 * and the process drawing the bar stopped being the same one.
	 *
	 * <p>
	 * Null is the ordinary state - before the first item, between items, and
	 * forever after the execution ends. Whoever writes a terminal status clears it,
	 * and whoever reads it ignores it unless the row is still RUNNING, so a worker
	 * that died cannot leave a stale percentage looking like live progress.
	 */
	@Column(name = "current_item_percent")
	private Integer currentItemPercent;

	@OneToMany(mappedBy = "execution", cascade = CascadeType.ALL, orphanRemoval = true)
	@Builder.Default
	@ToString.Exclude
	private List<Movement> movements = new ArrayList<>();

	@PrePersist
	void prePersist() {
		if (publicId == null) {
			publicId = UuidV7.generate();
		}

		if (createdAt == null) {
			createdAt = LocalDateTime.now(ClockHolder.clock());
		}

		if (availableAt == null) {
			availableAt = createdAt;
		}

		if (priority == null) {
			priority = 0;
		}

		if (claimCount == null) {
			claimCount = 0;
		}

		if (cancelRequested == null) {
			cancelRequested = false;
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

		if (repairedItems == null) {
			repairedItems = 0;
		}
	}
}