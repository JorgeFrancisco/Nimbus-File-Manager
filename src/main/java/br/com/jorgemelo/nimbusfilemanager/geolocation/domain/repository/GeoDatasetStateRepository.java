package br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.GeoDatasetState;

/**
 * The one row describing the installed geographic dataset.
 *
 * <p>
 * Reads and writes go through {@link GeoDatasetState#SINGLETON_ID} rather than
 * through a search: there is one installation, and asking for "the first row" of
 * a table that can only hold one would be describing the constraint twice.
 */
public interface GeoDatasetStateRepository extends JpaRepository<GeoDatasetState, Short> {

	default Optional<GeoDatasetState> findInstalled() {
		return findById(GeoDatasetState.SINGLETON_ID);
	}

	/**
	 * Declares the installation finished - the last durable write of a successful
	 * run, and the only one that makes the fast path available to the next.
	 *
	 * <p>
	 * A statement of its own rather than a save of the loaded entity: it runs after
	 * the transaction that wrote the row has long committed, and re-saving an
	 * entity read back then would carry whatever else that snapshot held.
	 *
	 * <p>
	 * It flushes and clears because a statement bypasses the persistence context: a
	 * caller that had already loaded the state would go on holding the copy that
	 * says unfinished, and the answer to "may this installation be skipped over?"
	 * would be the value from before the mark.
	 *
	 * <p>
	 * <b>It carries its own transaction, unlike the modifying queries elsewhere in
	 * this project.</b> They have none of their own either - a modifying query
	 * never does - but every one of them is called by a service that already
	 * opened one. This one is called by an installation that deliberately runs
	 * outside a transaction, because importing a worldwide dataset and publishing
	 * files on disk cannot share a commit. So there is nothing here to join, and
	 * without this annotation the flush above fails at the last write of an
	 * otherwise successful run - leaving a dataset fully imported and permanently
	 * unfinished, which is the reimport this whole design exists to prevent.
	 *
	 * @return how many rows it marked, which is 0 when no installation is on record
	 */
	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update GeoDatasetState state set state.complete = true where state.id = :id")
	int markComplete(@Param("id") short id);
}