package br.com.jorgemelo.nimbusfilemanager.shared.domain.model;

import java.time.LocalDateTime;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.ClockHolder;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
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
 * The physical placement of a media file on disk (current/original/inventory
 * paths). Renamed from {@code MediaLocation} to end the name collision with
 * {@link MediaGeoLocation}, which is the place in the world.
 *
 * <p>
 * Because {@code catalog_file.file_key} is UNIQUE and is the path itself, a
 * file has exactly one placement: the relationship is 1:1 and shares the
 * identity of {@link CatalogFile} (catalog_file_id), the same pattern used by
 * {@code media}, {@code photo} and {@code video}. The DB now guarantees this
 * uniqueness.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "catalog_file_location")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class CatalogFileLocation {

	@Id
	@Column(name = "catalog_file_id")
	@EqualsAndHashCode.Include
	private Long id;

	@MapsId
	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "catalog_file_id")
	@ToString.Exclude
	private CatalogFile catalogFile;

	@Column(name = "current_path", nullable = false)
	private String currentPath;

	@Column(name = "current_folder", nullable = false)
	private String currentFolder;

	@Column(name = "original_path", nullable = false)
	private String originalPath;

	@Column(name = "original_folder", nullable = false)
	private String originalFolder;

	@Column(name = "inventory_path")
	private String inventoryPath;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@PrePersist
	void prePersist() {
		if (updatedAt == null) {
			updatedAt = LocalDateTime.now(ClockHolder.clock());
		}
	}
}