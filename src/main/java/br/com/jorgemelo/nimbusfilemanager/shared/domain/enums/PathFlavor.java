package br.com.jorgemelo.nimbusfilemanager.shared.domain.enums;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;

/**
 * Which rules decide whether two paths name the same place.
 *
 * <p>
 * It travels with the row rather than being read from the host, and that is the
 * whole point: a catalog written on Windows keeps being compared under Windows
 * rules when the same database is later opened somewhere else. Deciding it from
 * the running system would silently change what a stored path means.
 *
 * <p>
 * Derived from the file system a path belongs to instead of from
 * {@code os.name}, so a path taken from a non-default file system - a test one,
 * a mounted image - answers for itself. There is one way to obtain it and it is
 * here; asking the operating system anywhere else would be a second authority
 * that eventually disagrees with this one.
 */
public enum PathFlavor {

	/** Both separators, and case never tells two names apart. */
	WINDOWS,

	/** One separator, and case is significant. */
	POSIX;

	private static final String WINDOWS_SEPARATOR = String.valueOf((char) 92);

	public static PathFlavor of(FileSystem fileSystem) {
		return WINDOWS_SEPARATOR.equals(fileSystem.getSeparator()) ? WINDOWS : POSIX;
	}

	public static PathFlavor of(Path path) {
		return of(path.getFileSystem());
	}

	/**
	 * The flavor of paths this process observes. For a path that is being read
	 * from the file system right now - never for one being read back from a row,
	 * which carries the flavor it was written with.
	 */
	public static PathFlavor current() {
		return of(FileSystems.getDefault());
	}
}