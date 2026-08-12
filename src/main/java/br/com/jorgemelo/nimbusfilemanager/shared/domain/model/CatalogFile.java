package br.com.jorgemelo.nimbusfilemanager.shared.domain.model;

import java.time.Instant;
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
	 * Optimistic-lock version. CatalogFile is updated by concurrent, non-serialized
	 * flows (inventory watcher, metadata rebuild, organization, rename detection) -
	 * none share a global lock - so a lost update is a real risk. Bulk updates
	 * (e.g. mark_catalog_files_missing) bump this column explicitly so already-loaded
	 * entities become stale instead of clobbering the change.
	 */
	@Version
	@Column(name = "version", nullable = false)
	private Long version;

	/**
	 * Which generation of the bytes everything derived from this file was
	 * computed from.
	 *
	 * <p>
	 * Not {@link #version}, which is the optimistic lock and moves on every write
	 * including a rename, and not {@code analysisVersion}, which is about the
	 * algorithm and stays right across an edit. It advances on one thing: a
	 * digest that disagrees with the one held. Learning a first digest does not
	 * move it - nothing was proved to have happened - and neither does a change
	 * of address.
	 */
	@Builder.Default
	@Column(name = "content_revision", nullable = false)
	private Long contentRevision = 1L;

	@Column(name = "catalog_file_public_id", nullable = false, unique = true, updatable = false)
	private UUID catalogFilePublicId;

	@Column(nullable = false, length = 50)
	private String extension;

	@Column(name = "size_bytes", nullable = false)
	private Long sizeBytes;

	@Column(name = "sha256", length = 64)
	private String sha256;

	@Column(name = "mime_type", length = 100)
	private String mimeType;

	@Column(name = "created_at")
	private Instant createdAt;

	@Column(name = "modified_at", nullable = false)
	private Instant modifiedAt;

	@Column(name = "imported_at", nullable = false)
	private Instant importedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "file_type", nullable = false, length = 30)
	private FileType fileType;

	/**
	 * Lifecycle state, replacing the former {@code exists_flag} + {@code deleted}
	 * booleans. See {@link LifecycleStatus} for the invariants.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "lifecycle_status", nullable = false, length = 30)
	@Builder.Default
	private LifecycleStatus lifecycleStatus = LifecycleStatus.ACTIVE;

	/**
	 * When {@link #lifecycleStatus} last changed. Stamped only on a real transition
	 * (see the {@code mark*} methods and the bulk lifecycle door), so it
	 * anchors the retention window the catalog purge uses to age MISSING records -
	 * a file that stays MISSING across successive reconciles keeps its original
	 * timestamp instead of having the clock reset on every pass.
	 */
	@Column(name = "lifecycle_changed_at")
	private Instant lifecycleChangedAt;

	@Column(name = "last_analysis")
	private Instant lastAnalysis;

	@Column(name = "analysis_version", length = 50)
	private String analysisVersion;

	/*
	 * Where the file is now. One placement per file, enforced by a unique constraint
	 * on catalog_file_id rather than by the path being the identity - which is the
	 * whole point of the split: two files may legitimately name one path when only
	 * one of them is actually there.
	 *
	 * REMOVE and nothing else. Saving a file must not be a way to write where it
	 * is: an aggregate read before a move still names the old path, and a cascade
	 * that carried it would put the move back - written to disk, recorded as a
	 * fact, and then undone in the catalog. Placement is written by the door that
	 * owns it, and the first one is written explicitly by the pass that catalogues
	 * the file.
	 *
	 * REMOVE stays because deletion is the aggregate's: a file leaving takes its
	 * placement with it. The foreign key says the same, and both are
	 * needed - without the cascade the placement lingers in the session pointing at
	 * a row that is going, and the flush refuses it.
	 */
	@OneToOne(mappedBy = "catalogFile", cascade = CascadeType.REMOVE, fetch = FetchType.LAZY)
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
		if (catalogFilePublicId == null) {
			catalogFilePublicId = UuidV7.generate();
		}

		if (importedAt == null) {
			importedAt = Instant.now(ClockHolder.clock());
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

			this.lifecycleChangedAt = Instant.now(ClockHolder.clock());
		}
	}

	/** Mark as explicitly DELETED (soft delete). */
	public void markDeleted() {
		if (this.lifecycleStatus != LifecycleStatus.DELETED) {
			this.lifecycleStatus = LifecycleStatus.DELETED;

			this.lifecycleChangedAt = Instant.now(ClockHolder.clock());
		}
	}
}