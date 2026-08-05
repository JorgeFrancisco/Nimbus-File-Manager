package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns a path into the numbers PostgreSQL uses for advisory locks.
 *
 * <p>
 * The database knows nothing about folders containing files, so the whole
 * hierarchy is built here: a path is locked together with every ancestor up to
 * the volume root, which is what makes a rename inside {@code d:\fotos\2008}
 * collide with an inventory of {@code d:\fotos}. The chain is as deep as a
 * library path, which is nothing.
 *
 * <p>
 * The dangerous failure is not a hash collision - that only makes two unrelated
 * operations wait for each other, which is safe. It is <em>one path producing
 * two different keys</em>, because then two operations both believe they hold
 * the tree and neither is wrong about the lock they took. Every normalisation
 * step below exists to close one way that could happen, and the tests exist to
 * prove they all collapse onto the same number.
 */
public final class OperationPathKey {

	private static final int KEY_BYTES = Long.BYTES;

	private OperationPathKey() {
	}

	/**
	 * The one spelling of a path that both processes must agree on.
	 *
	 * <p>
	 * {@code toRealPath} is what resolves a junction, a symlink or a reparse
	 * point to the file the system actually means - locking only the name it was
	 * reached by would leave the other name unguarded. It also fixes the casing to
	 * whatever is on disk. When the path does not exist yet (a move target, a
	 * folder about to be created) the walk falls back to the nearest ancestor that
	 * does, and when the volume is gone entirely it degrades to plain
	 * normalisation - refusing to lock would be worse than locking by name.
	 */
	public static String canonical(Path path) {
		Path absolute = path.toAbsolutePath().normalize();

		// No separator or trailing-slash handling of its own: normalize() already
		// collapsed "." and "..", chose the platform separator and dropped a trailing
		// one, while leaving a drive root the separator it is entitled to. A second
		// implementation here would only be one more place to disagree with.
		return realPathOf(absolute).toString().toLowerCase(Locale.ROOT);
	}

	/**
	 * The advisory key of a single canonical path: the first eight bytes of its
	 * SHA-256. Not {@code String.hashCode()} - that is not guaranteed stable
	 * across JVM versions, and two processes disagreeing on a key is precisely the
	 * failure this class exists to prevent.
	 */
	public static long key(String canonicalPath) {
		byte[] digest = sha256(canonicalPath.getBytes(StandardCharsets.UTF_8));

		long key = 0;

		for (int index = 0; index < KEY_BYTES; index++) {
			key = (key << 8) | (digest[index] & 0xFF);
		}

		return key;
	}

	/**
	 * Every key an operation on {@code paths} has to hold, and in which mode.
	 *
	 * <p>
	 * The path itself is taken <em>exclusively</em>; its ancestors are taken
	 * <em>shared</em>, as a declaration of intent rather than a claim. That
	 * distinction is the difference between a working lock and one that serialises
	 * the whole disk: shared locks do not conflict with each other, so two
	 * operations in sibling folders both hold the parent and proceed - while an
	 * operation on the parent itself wants it exclusively and therefore waits, and
	 * is waited for. Taking ancestors exclusively would have made every pair of
	 * operations on one volume conflict, since they all share a root.
	 *
	 * <p>
	 * Sorted, and that is the point of the {@link TreeSet}. Two operations needing
	 * the same pair of paths would deadlock if one took them in the order it
	 * received them and the other in reverse; with a total order over the keys
	 * themselves, circular waiting cannot form, and no timeout is needed to break
	 * something that never happens.
	 */
	public static Set<PathLockKey> chainOf(List<Path> paths) {
		Set<Long> exclusive = new TreeSet<>();
		Set<Long> shared = new TreeSet<>();

		for (Path path : paths) {
			Path canonical = Path.of(canonical(path));

			exclusive.add(key(canonical(canonical)));

			for (Path current = canonical.getParent(); current != null; current = current.getParent()) {
				shared.add(key(canonical(current)));
			}
		}

		Set<PathLockKey> chain = new TreeSet<>();

		exclusive.forEach(key -> chain.add(new PathLockKey(key, true)));

		// A path that is an ancestor of another path in the same request is already
		// held exclusively, which is the stronger of the two - asking for it shared
		// as well would be redundant.
		shared.stream().filter(key -> !exclusive.contains(key)).forEach(key -> chain.add(new PathLockKey(key, false)));

		return chain;
	}

	private static Path realPathOf(Path absolute) {
		for (Path current = absolute; current.getParent() != null; current = current.getParent()) {
			try {
				Path real = current.toRealPath();

				return current == absolute ? real : real.resolve(current.relativize(absolute));
			} catch (IOException _) {
				// Does not exist yet, or cannot be read: try the parent. A move target is
				// routinely absent at the moment its lock is taken.
			}
		}

		return absolute;
	}

	private static byte[] sha256(byte[] content) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(content);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is required to build operation lock keys", exception);
		}
	}
}