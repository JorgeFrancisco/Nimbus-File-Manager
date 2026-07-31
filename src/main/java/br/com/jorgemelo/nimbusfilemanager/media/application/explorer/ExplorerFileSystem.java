package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The disk operations the explorer deletion needs, behind an interface for the
 * same reason {@code FileDateReader} exists in the metadata package: what
 * happens when the filesystem refuses - an unreadable folder, a file that
 * cannot be removed - is a real branch of the service, and the only honest way
 * to exercise it is to let a test stand in for the disk. Production uses
 * {@link DefaultExplorerFileSystem}.
 */
interface ExplorerFileSystem {

	boolean isDirectory(Path path);

	/** Every regular file under {@code folder}, links never followed. */
	List<Path> listFiles(Path folder) throws IOException;

	/** Deletes depth-first, returning how many regular files went. */
	int deleteRecursively(Path path) throws IOException;

	/**
	 * Removes {@code folder} and the folders under it once no file is left anywhere
	 * inside - the state a quarantined folder ends in. Does nothing while any file
	 * remains, so a folder that only gave up part of its contents stays put.
	 */
	void deleteEmptyTree(Path folder) throws IOException;
}