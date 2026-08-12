package br.com.jorgemelo.nimbusfilemanager.shared.domain.model;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import lombok.ToString;

/**
 * Something that happened to a file, written once and never revised.
 *
 * <p>
 * The current state of a file is a projection kept elsewhere - where it is now
 * lives in {@link CatalogFileLocation}, what state it is in lives on
 * {@link CatalogFile}. This is the record of how it got there, and it is the
 * only place that remembers a path the file no longer has.
 *
 * <p>
 * {@code eventType}, {@code source} and {@code evidenceKind} are strings rather
 * than enums on purpose, matching the columns: a new kind of fact - or a new
 * thing observing them, or a new proof to establish them - should not need a
 * schema change nor a new constant to be recorded. Freezing them here would put
 * back, in Java, the closed set the table deliberately refuses.
 *
 * <p>
 * There are no setters for a reason that is not style: a fact that can be
 * edited is not a fact. Correcting one means recording another.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "catalog_file_event")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class CatalogFileEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	/**
	 * Identity of the fact, and the key a retry is recognised by: the same
	 * operation attempted twice carries the same one, so the second attempt
	 * recognises its own work instead of recording it again.
	 */
	@Column(name = "catalog_file_event_public_id", nullable = false, unique = true, updatable = false)
	private UUID catalogFileEventPublicId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "catalog_file_id", nullable = false, updatable = false)
	@ToString.Exclude
	private CatalogFile catalogFile;

	@Column(name = "event_type", nullable = false, length = 64, updatable = false)
	private String eventType;

	/** Where the file was before, when the fact had a before. */
	@Column(name = "old_path", updatable = false)
	private String oldPath;

	/** Where it was after, when the fact had an after. */
	@Column(name = "new_path", updatable = false)
	private String newPath;

	/**
	 * The configured root the fact was observed under - context of origin, not
	 * where the file is now, and absent when no root was involved.
	 */
	@Column(name = "inventory_root_path", updatable = false)
	private String inventoryRootPath;

	/** When it happened, according to whoever observed it. */
	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	/**
	 * When the database stored it, which it decides itself and may be much later
	 * than the fact.
	 */
	@Generated(event = { EventType.INSERT })
	@Column(name = "recorded_at")
	private Instant recordedAt;

	@Column(name = "source", nullable = false, length = 64, updatable = false)
	private String source;

	/**
	 * What proof stands behind the classification - a different question from
	 * {@link #source}, which says who, and from {@link #eventType}, which says
	 * what. A string for the same reason those are.
	 */
	@Column(name = "evidence_kind", nullable = false, length = 64, updatable = false)
	private String evidenceKind;

	/**
	 * What the filesystem offered as this file's identity at that moment, if
	 * anything. Evidence for recognising the file again - never a relational
	 * identity, and never required.
	 */
	@Column(name = "filesystem_identity_kind", length = 64, updatable = false)
	private String filesystemIdentityKind;

	@Column(name = "filesystem_identity_scope", length = 255, updatable = false)
	private String filesystemIdentityScope;

	@Column(name = "filesystem_identity_value", length = 255, updatable = false)
	private String filesystemIdentityValue;

	@PrePersist
	void prePersist() {
		if (catalogFileEventPublicId == null) {
			catalogFileEventPublicId = UuidV7.generate();
		}
	}
}