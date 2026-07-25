package br.com.jorgemelo.nimbusfilemanager.shared.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Central, reusable rule for whether a filesystem path is a real, physical
 * file/directory the Nimbus File Manager may process. Everything indirect is
 * rejected and must never be inventoried, monitored, displayed, organized,
 * moved, renamed, deleted, hashed, have metadata/thumbnails extracted or be
 * considered for duplicates:
 * <ul>
 * <li>symbolic links (file and directory);</li>
 * <li>Windows junctions / reparse points (directory);</li>
 * <li>{@code .lnk} shortcut files (case-insensitive).</li>
 * </ul>
 *
 * <p>
 * Detection uses only portable NIO and never follows the link to its target:
 * {@link Files#isSymbolicLink(Path)} for symlinks, a case-insensitive
 * {@code .lnk} suffix for shortcuts, and a real-path comparison (with vs.
 * without {@link LinkOption#NOFOLLOW_LINKS}) to catch directory reparse points
 * such as Windows junctions.
 *
 * <p>
 * <b>Windows limitation:</b> junction/reparse detection is best-effort — it
 * depends on the JVM being able to resolve the real path and cannot be created
 * portably in tests, so it is only exercised where the OS allows it. Symbolic
 * links, {@code .lnk} files and circular symlinks are fully handled portably.
 */
public final class PhysicalFilePolicy {

	private PhysicalFilePolicy() {
	}

	/**
	 * @return {@code true} only for real, physical files/directories safe to
	 *         process.
	 */
	public static boolean isProcessable(Path path) {
		if (path == null) {
			return false;
		}

		if (isShortcut(path)) {
			return false;
		}

		try {
			if (Files.isSymbolicLink(path)) {
				return false;
			}

			return !isReparseDirectory(path);
		} catch (RuntimeException _) {
			// If the path cannot be inspected safely, treat it as not processable.
			return false;
		}
	}

	private static boolean isShortcut(Path path) {
		Path name = path.getFileName();

		return name != null && name.toString().toLowerCase(Locale.ROOT).endsWith(".lnk");
	}

	/**
	 * A directory whose real path differs when the final component is not followed
	 * - i.e. a junction / reparse point. Regular files were already screened by
	 * {@link Files#isSymbolicLink(Path)}.
	 */
	private static boolean isReparseDirectory(Path path) {
		if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
			return false;
		}

		try {
			return !path.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(path.toRealPath());
		} catch (IOException _) {
			// Unresolvable directory (e.g. dangling reparse point): treat as unsafe.
			return true;
		}
	}
}