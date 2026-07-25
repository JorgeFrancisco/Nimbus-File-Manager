package br.com.jorgemelo.nimbusfilemanager.telemetry.domain.model;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Performance telemetry of a measured processing run: elapsed time, throughput,
 * the tuning parameters in effect and the photo-hash decode counters.
 *
 * <p>
 * Split out of {@link Execution} because it is a distinct concept - how a run
 * performed, not what it did - and it is populated only for measured processing
 * runs (inventory/fingerprint). Organization and reconcile executions never
 * have a row, so {@code execution} stays free of these mostly-null columns.
 * Only the telemetry/statistics screen joins this table; the frequent execution
 * history and dashboard queries never touch it. The relationship is 1:1 sharing
 * the identity of {@link Execution} (execution_id), the same pattern used by
 * {@link CatalogFileLocation}.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "execution_metrics")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ExecutionMetrics {

	@Id
	@Column(name = "execution_id")
	@EqualsAndHashCode.Include
	private Long id;

	@MapsId
	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "execution_id")
	@ToString.Exclude
	private Execution execution;

	@Column(name = "duration_millis")
	private Long durationMillis;

	@Column(name = "files_per_second")
	private Double filesPerSecond;

	@Column(name = "workers")
	private Integer workers;

	@Column(name = "chunk_size")
	private Integer chunkSize;

	@Column(name = "ffmpeg_photo_hash_limit")
	private Integer ffmpegPhotoHashLimit;

	@Column(name = "ffprobe_video_limit")
	private Integer ffprobeVideoLimit;

	@Column(name = "photo_hash_jvm_decodable")
	private Long photoHashJvmDecodable;

	@Column(name = "photo_hash_ffmpeg_only")
	private Long photoHashFfmpegOnly;

	@Column(name = "photo_hash_failures")
	private Long photoHashFailures;
}