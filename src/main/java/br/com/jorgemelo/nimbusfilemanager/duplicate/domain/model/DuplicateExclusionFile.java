package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
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
 * A file the user asked never to be offered as a duplicate, and the content
 * they asked it about.
 *
 * <p>
 * The revision is what makes the judgement honest over time. It was made while
 * looking at one picture; when the bytes behind that path are replaced, the
 * judgement is about something nobody has seen. It is not deleted - the user did
 * say it - it simply stops applying, which is the difference between forgetting
 * a preference and knowing what it was about.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "duplicate_exclusion_file")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class DuplicateExclusionFile {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	@Column(name = "duplicate_exclusion_file_public_id", nullable = false, unique = true, updatable = false)
	private UUID duplicateExclusionFilePublicId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "catalog_file_id", nullable = false, updatable = false)
	@ToString.Exclude
	private CatalogFile catalogFile;

	/** The generation of the bytes the judgement was made about. */
	@Column(name = "content_revision", nullable = false, updatable = false)
	private Long contentRevision;

	@Generated(event = { EventType.INSERT })
	@Column(name = "created_at")
	private Instant createdAt;

	@PrePersist
	void prePersist() {
		if (duplicateExclusionFilePublicId == null) {
			duplicateExclusionFilePublicId = UuidV7.generate();
		}
	}
}