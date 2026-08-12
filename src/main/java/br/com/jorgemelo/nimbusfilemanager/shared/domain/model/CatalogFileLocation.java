package br.com.jorgemelo.nimbusfilemanager.shared.domain.model;

import java.time.Instant;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.ClockHolder;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FilesystemIdentityKind;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * The last place a file was seen, which is not the same as where it is.
 *
 * <p>
 * A file that went missing keeps the row: it is what lets the catalog look for
 * it again, and what a screen shows when asked where the file used to be.
 * Whether the file is actually there is the lifecycle's answer, not this one -
 * so two rows may name the same path while only one of them is present, and the
 * database deliberately does not forbid it.
 *
 * <p>
 * {@code currentPath} is the only authority here. The canonical key and the
 * containing folder are derived from it by the database and are read-only in
 * this class: stored side by side and written by hand, they could disagree with
 * the path they describe, and nobody would be at fault when they did.
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

	/**
	 * The identity of the file this places, and no identity of its own - the same
	 * shape as the other components of a catalogued file. A key that could differ
	 * from {@code catalog_file_id} is a key something can be paged or joined by
	 * mistakenly, which is exactly what a reconcile once did.
	 */
	@Id
	@Column(name = "catalog_file_id")
	@EqualsAndHashCode.Include
	private Long id;

	@MapsId
	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "catalog_file_id", nullable = false)
	@ToString.Exclude
	private CatalogFile catalogFile;

	/** Spelled the way the filesystem spells it, and already normalized. */
	@Column(name = "current_path", nullable = false)
	private String currentPath;

	/**
	 * Which rules canonicalize this row. It travels with the row so a catalog
	 * written on one platform keeps being read under the rules it was written
	 * with.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "path_flavor", nullable = false, length = 16)
	private PathFlavor pathFlavor;

	/**
	 * Computed by the database from {@link #currentPath} and {@link #pathFlavor}.
	 * Never written from here - and read back after every insert and update, so an
	 * entity in memory never disagrees with the row it came from.
	 */
	@Generated(event = { EventType.INSERT, EventType.UPDATE })
	@Column(name = "path_key")
	private String pathKey;

	/** Computed by the database, for the same reason as {@link #pathKey}. */
	@Generated(event = { EventType.INSERT, EventType.UPDATE })
	@Column(name = "current_folder")
	private String currentFolder;

	/**
	 * What the operating system calls the object at {@link #currentPath}, when
	 * something was in a position to observe it.
	 *
	 * <p>
	 * It belongs to the location and not to the file because it describes the
	 * physical thing that is there now, and that thing can be replaced while the
	 * catalogued file stays the same one: a move to another volume, or a
	 * copy-and-delete, leaves the same {@code catalog_file_public_id} pointing at
	 * an object with a different identity.
	 *
	 * <p>
	 * Null is the ordinary case, not a defect: nothing opens a library's worth of
	 * files to ask them their id, so a row carries one only once something that
	 * already knew it passed by. A stored identity that has gone stale is harmless
	 * rather than misleading - the sequence number the operating system builds
	 * into the id means it is never handed to another file.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "filesystem_identity_kind", length = 32)
	private FilesystemIdentityKind filesystemIdentityKind;

	/** Where {@link #filesystemIdentityValue} is unique - for Windows, a volume. */
	@Column(name = "filesystem_identity_scope")
	private String filesystemIdentityScope;

	@Column(name = "filesystem_identity_value")
	private String filesystemIdentityValue;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/**
	 * The file's name, which is the last segment of where it is.
	 *
	 * <p>
	 * Derived rather than stored, and here rather than on the file: a name changes
	 * exactly when the path changes, because it is part of it. Keeping a copy
	 * beside the path is how the catalog ended up with two places to rename and
	 * one of them forgotten.
	 *
	 * <p>
	 * Which characters separate segments is the row's own flavor to say - a
	 * backslash ends a segment on Windows and belongs to the name on POSIX - so
	 * this never asks the running platform.
	 */
	public String fileName() {
		if (currentPath == null) {
			return null;
		}

		int cut = PathFlavor.WINDOWS.equals(pathFlavor)
				? Math.max(currentPath.lastIndexOf('/'), currentPath.lastIndexOf((char) 92))
				: currentPath.lastIndexOf('/');

		return currentPath.substring(cut + 1);
	}

	/**
	 * Agrees with a placement that has already been applied to the row.
	 *
	 * <p>
	 * The door that moves a file writes the row directly and answers with the
	 * placement as it now stands. An entry read before that still names where the
	 * file was, and saving it merges the stale placement back over the row - so the
	 * move is written to disk, recorded as a fact, and then undone in the catalog.
	 * The file is then listed at a source it has already left, and every later pass
	 * plans the same move again.
	 *
	 * <p>
	 * Takes the derived values rather than recomputing them, for the reason
	 * {@link #pathKey} gives: they are the database's to say, and a second opinion
	 * here is how two answers to one question start.
	 */
	public void placedAt(String currentPath, String pathKey, String currentFolder) {
		this.currentPath = currentPath;
		this.pathKey = pathKey;
		this.currentFolder = currentFolder;
	}

	@PrePersist
	void prePersist() {
		if (updatedAt == null) {
			updatedAt = Instant.now(ClockHolder.clock());
		}
	}
}