package br.com.jorgemelo.nimbusfilemanager.worker.domain.model;

import java.time.LocalDateTime;

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
 * One row per worker process that has said it is alive.
 *
 * <p>
 * The application writes nothing here and the worker writes nothing else: this
 * is the only fact that crosses the process boundary in that direction, and it
 * carries no work, no ownership and no progress. What it lets a screen say is
 * the difference between "your request is being processed" and "your request is
 * queued and there is nobody to process it" - two situations that looked
 * identical from the outside, because a PENDING row looks the same either way.
 *
 * <p>
 * The id is the same string the queue writes into {@code claimed_by}, so a
 * claim and a heartbeat can be read as being about the same process. Freshness
 * is not stored: whoever asks decides how old is too old, and a boolean written
 * here would be a decision frozen at write time by the process least able to
 * make it.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "worker_instance")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class WorkerInstance {

	@Id
	@Column(name = "worker_id", nullable = false, length = 128)
	@EqualsAndHashCode.Include
	private String workerId;

	@Column(name = "last_seen_at", nullable = false)
	private LocalDateTime lastSeenAt;
}