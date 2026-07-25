package br.com.jorgemelo.nimbusfilemanager.shared.domain.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.ClockHolder;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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
@Table(name = "catalog_file")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class CatalogFile {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	/**
	 * Optimistic-lock version. CatalogFile is updated by concurrent,
	 * non-serialized flows (inventory watcher, metadata rebuild, organization,
	 * rename detection) - none share a global lock - so a lost update is a real
	 * risk. Bulk updates (e.g. markMissingByIds) bump this column explicitly so
	 * already-loaded entities become stale instead of clobbering the change.
	 */
	@Version
	@Column(name = "version", nullable = false)
	private Long version;

	@Column(name = "public_id", nullable = false, unique = true, updatable = false)
	private UUID publicId;

	@Column(name = "file_key", nullable = false, unique = true, length = 500)
	private String fileKey;

	@Column(name = "file_name", nullable = false, length = 500)
	private String fileName;

	@Column(nullable = false, length = 50)
	private String extension;

	@Column(name = "size_bytes", nullable = false)
	private Long sizeBytes;

	@Column(name = "sha256", length = 64)
	private String sha256;

	@Column(name = "md5", length = 32)
	private String md5;

	@Column(name = "mime_type", length = 100)
	private String mimeType;

	@Column(name = "created_at")
	private LocalDateTime createdAt;

	@Column(name = "modified_at", nullable = false)
	private LocalDateTime modifiedAt;

	@Column(name = "imported_at", nullable = false)
	private LocalDateTime importedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "file_type", nullable = false, length = 30)
	private FileType fileType;

	/**
	 * Lifecycle state, replacing the former {@code exists_flag} +
	 * {@code deleted} booleans. See {@link LifecycleStatus} for the invariants.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "lifecycle_status", nullable = false, length = 30)
	@Builder.Default
	private LifecycleStatus lifecycleStatus = LifecycleStatus.ACTIVE;

	/**
	 * When {@link #lifecycleStatus} last changed. Stamped only on a real transition
	 * (see the {@code mark*} methods and the bulk {@code markMissingByIds}), so it
	 * anchors the retention window the catalog purge uses to age MISSING records -
	 * a file that stays MISSING across successive reconciles keeps its original
	 * timestamp instead of having the clock reset on every pass.
	 */
	@Column(name = "lifecycle_changed_at")
	private LocalDateTime lifecycleChangedAt;

	@Column(name = "last_analysis")
	private LocalDateTime lastAnalysis;

	@Column(name = "analysis_version", length = 50)
	private String analysisVersion;

	/*
	 * The file's placement on disk. 1:1 (catalog_file.file_key is UNIQUE and is the
	 * path itself), so a single CatalogFileLocation, not a collection - the DB now
	 * enforces one placement per file. Renamed from the former "locations" list.
	 */
	@OneToOne(mappedBy = "catalogFile", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
	@ToString.Exclude
	private CatalogFileLocation location;

	/*
	 * JPA's default fetch type for @OneToOne is EAGER (unlike @OneToMany, which
	 * already defaults to LAZY) - without an explicit fetch = LAZY, any code that
	 * loads a CatalogFile outside a projection DTO (e.g.
	 * repository.findById/findAll) pays 3 extra SELECTs it usually doesn't need.
	 */
	@OneToOne(mappedBy = "catalogFile", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
	@ToString.Exclude
	private MediaMetadata metadata;

	@OneToOne(mappedBy = "catalogFile", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
	@ToString.Exclude
	private Photo photo;

	@OneToOne(mappedBy = "catalogFile", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
	@ToString.Exclude
	private Video video;

	@OneToMany(mappedBy = "catalogFile")
	@Builder.Default
	@ToString.Exclude
	private List<Movement> movements = new ArrayList<>();

	@PrePersist
	void prePersist() {
		if (publicId == null) {
			publicId = UuidV7.generate();
		}

		if (importedAt == null) {
			importedAt = LocalDateTime.now(ClockHolder.clock());
		}

		if (lifecycleStatus == null) {
			lifecycleStatus = LifecycleStatus.ACTIVE;
		}
	}

	/**
	 * True only in {@link LifecycleStatus#ACTIVE} - present on disk, not removed.
	 */
	public boolean isActive() {
		return lifecycleStatus == LifecycleStatus.ACTIVE;
	}

	/** True in {@link LifecycleStatus#MISSING} - absent from disk, not removed. */
	public boolean isMissing() {
		return lifecycleStatus == LifecycleStatus.MISSING;
	}

	/** True in {@link LifecycleStatus#DELETED} - explicitly removed. */
	public boolean isDeleted() {
		return lifecycleStatus == LifecycleStatus.DELETED;
	}

	/** Promote back to ACTIVE (file found/re-found on disk). */
	public void markActive() {
		if (this.lifecycleStatus != LifecycleStatus.ACTIVE) {
			this.lifecycleStatus = LifecycleStatus.ACTIVE;

			this.lifecycleChangedAt = LocalDateTime.now(ClockHolder.clock());
		}
	}

	/**
	 * Mark as MISSING (absent from disk), preserving the DELETED invariant: a
	 * DELETED file is never downgraded to MISSING. Only a real ACTIVE -&gt; MISSING
	 * transition stamps {@link #lifecycleChangedAt}; re-confirming an
	 * already-MISSING file keeps its original timestamp so the retention clock does
	 * not reset.
	 */
	public void markMissing() {
		if (this.lifecycleStatus == LifecycleStatus.ACTIVE) {
			this.lifecycleStatus = LifecycleStatus.MISSING;

			this.lifecycleChangedAt = LocalDateTime.now(ClockHolder.clock());
		}
	}

	/** Mark as explicitly DELETED (soft delete). */
	public void markDeleted() {
		if (this.lifecycleStatus != LifecycleStatus.DELETED) {
			this.lifecycleStatus = LifecycleStatus.DELETED;

			this.lifecycleChangedAt = LocalDateTime.now(ClockHolder.clock());
		}
	}
}