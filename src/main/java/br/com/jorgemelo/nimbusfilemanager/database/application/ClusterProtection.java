package br.com.jorgemelo.nimbusfilemanager.database.application;

import static br.com.jorgemelo.nimbusfilemanager.database.application.constants.EmbeddedDatabaseConstants.VERSION_FILE;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Answers whether a folder holds a PostgreSQL cluster, so that nothing deletes
 * one by accident.
 *
 * <p>
 * The workspace is full of things the application recreates without asking -
 * thumbnails, temp files, downloaded datasets, logs - and the routines that
 * clean them take a folder and empty it. The cluster now lives in that same
 * workspace and is the one thing in it that cannot be regenerated: losing it
 * loses the catalog. So a routine that clears a workspace subtree asks here
 * first and skips what it finds, and the initializer asks before running
 * {@code initdb} into a folder that may already have data.
 *
 * <p>
 * The check is by content rather than by path on purpose. A path comparison
 * only protects the cluster the code remembers to compare against, and it
 * breaks the moment a folder is renamed or the workspace moves - which is
 * exactly when a cleanup routine is most likely to be pointed somewhere
 * unexpected.
 */
public final class ClusterProtection {

	private ClusterProtection() {
	}

	/**
	 * Whether the given tree holds a cluster, either at its root or anywhere
	 * below it - deleting a parent destroys the cluster just as thoroughly as
	 * deleting the cluster itself.
	 */
	public static boolean holdsCluster(Path directory) {
		if (directory == null || !Files.isDirectory(directory)) {
			return false;
		}

		if (Files.isRegularFile(directory.resolve(VERSION_FILE))) {
			return true;
		}

		try (var entries = Files.walk(directory)) {
			return entries.anyMatch(ClusterProtection::isVersionFile);
		} catch (Exception _) {
			// A tree that cannot be read cannot be cleared safely either, and the
			// cluster may well be the part that refused: treat it as protected.
			return true;
		}
	}

	private static boolean isVersionFile(Path path) {
		Path name = path.getFileName();

		return name != null && VERSION_FILE.equals(name.toString()) && Files.isRegularFile(path);
	}
}