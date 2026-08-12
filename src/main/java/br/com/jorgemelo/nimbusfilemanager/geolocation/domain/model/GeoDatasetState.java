package br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model;

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
 * What the installed geographic dataset is: its version, where it came from,
 * when it was imported, and whether the installation that produced it ever
 * finished.
 *
 * <p>
 * <b>It is written by the transaction that writes the boundaries.</b> That is
 * the whole reason it exists. These facts used to live in a JSON file beside the
 * downloads, written after the import committed, and the gap between the two
 * media was not a small window: a run that imported the rows and then failed
 * left a database holding one dataset and a file describing the one before it,
 * with no authority anywhere able to say when the installed rows had arrived.
 * Here the row and the boundaries share a transaction, so they are true together
 * or neither is written.
 *
 * <p>
 * <b>Only what nothing else owns.</b> There is no record count, because
 * {@code COUNT(*)} answers that exactly and never drifts; no size, because the
 * geodata folder is the size; no last error, because the execution that failed
 * is where an error belongs. A field here would be a second copy of a fact with
 * an owner, and the second copy is the one that goes stale.
 *
 * <p>
 * <b>{@link #complete} is the commit point of an installation, not a detail of
 * it.</b> Importing the three levels, completing the dissolved territories and
 * publishing the downloaded files are separate transactions on purpose - a
 * territory that fails must not roll back a worldwide import - so the presence
 * of rows never meant the installation had finished. This says so explicitly:
 * it turns true after everything else succeeded, and a crash at any point before
 * that leaves a dataset the next run rebuilds rather than trusts.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "geo_dataset_state")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class GeoDatasetState {

	/** The only row this table ever holds; the check constraint says so too. */
	public static final short SINGLETON_ID = 1;

	/**
	 * Always {@link #SINGLETON_ID}: one installation is described at a time, and
	 * the database enforces it with a check constraint rather than trusting every
	 * writer to remember.
	 */
	@Id
	@EqualsAndHashCode.Include
	private Short id;

	@Column(name = "dataset_version", nullable = false, length = 50)
	private String datasetVersion;

	@Column(nullable = false, length = 40)
	private String source;

	@Column(nullable = false, length = 120)
	private String provider;

	@Column(nullable = false, length = 200)
	private String license;

	/**
	 * When the boundaries now installed were imported - written in their own
	 * transaction, so it can never describe a different set of rows than the one
	 * present.
	 */
	@Column(name = "imported_at", nullable = false)
	private LocalDateTime importedAt;

	@Column(nullable = false)
	private boolean complete;
}