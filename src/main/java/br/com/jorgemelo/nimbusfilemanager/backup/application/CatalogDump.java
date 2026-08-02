package br.com.jorgemelo.nimbusfilemanager.backup.application;

import java.nio.file.Path;

import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.DatabaseConnection;

/**
 * Writing and reading a logical dump of the catalog, as a port.
 *
 * <p>
 * The first implementation of the backup wrote one CSV per table with
 * {@code COPY}, to avoid depending on a second external binary. That reason
 * disappeared when the application started shipping PostgreSQL itself:
 * {@code pg_dump} now sits beside the server it dumps.
 *
 * <p>
 * What the change buys is the schema. A CSV carries rows and nothing else, so
 * it only loads back into tables shaped exactly as they were - a column renamed
 * by any later migration made the file unusable, and the catalog it held is
 * precisely the thing nobody can rebuild. A dump carries the tables, their
 * types, constraints and sequences with the rows, so it restores into an empty
 * database and the migrations then bring it forward.
 */
public interface CatalogDump {

	/**
	 * Writes the whole database to one file.
	 *
	 * @return whether the file was written
	 */
	boolean dump(Path target);

	/**
	 * Loads a dump back, replacing what is there.
	 *
	 * @return whether the database was restored
	 */
	boolean restore(Path source);

	/**
	 * Which database these act on.
	 *
	 * <p>
	 * Part of the contract because the restore destroys what it replaces, and a
	 * destructive operation that picks its own target has to be able to say what
	 * that target is - both to a test that pins it and to anyone diagnosing a
	 * backup that went somewhere unexpected.
	 */
	DatabaseConnection target();

	/**
	 * What the last failing command printed, or an empty string.
	 *
	 * <p>
	 * Part of the contract because these tools explain themselves and nothing
	 * else can: "the dump could not be read back" says what happened and never
	 * why, and the tool's own line - a version mismatch, a missing file, a
	 * refused connection - was landing in a log far above the failure.
	 */
	String lastOutput();

	/**
	 * Ends a dump that is still running.
	 *
	 * <p>
	 * Only a dump. Killing a restore halfway leaves a database that is neither
	 * what it was nor what it was becoming - the objects have been dropped and
	 * not yet recreated - so there is nothing safe to offer there.
	 *
	 * @return whether a running command was ended
	 */
	boolean cancelDump();

	/**
	 * Whether a dump can be read back in full.
	 *
	 * <p>
	 * Reading the archive index walks the whole file, so a truncated or damaged
	 * dump fails here instead of on the day it is needed. A backup nobody can
	 * restore is worse than no backup, because it is trusted.
	 */
	boolean readable(Path dump);
}