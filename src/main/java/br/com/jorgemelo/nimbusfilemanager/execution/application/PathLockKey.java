package br.com.jorgemelo.nimbusfilemanager.execution.application;

/**
 * One advisory lock an operation needs: which key, and whether it claims the
 * path or merely declares it is working somewhere below it.
 *
 * <p>
 * Comparable by key alone, because acquisition order is what prevents deadlock
 * and it has to be the same total order for everyone, regardless of the mode
 * each caller happens to want a given key in.
 *
 * @param exclusive true for the path the operation actually works on, false for
 * an ancestor held only so that an operation on the ancestor itself waits
 */
public record PathLockKey(long key, boolean exclusive) implements Comparable<PathLockKey> {

	@Override
	public int compareTo(PathLockKey other) {
		return Long.compare(key, other.key);
	}
}