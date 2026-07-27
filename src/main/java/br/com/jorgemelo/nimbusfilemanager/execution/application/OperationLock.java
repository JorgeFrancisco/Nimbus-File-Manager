package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.util.Set;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;

public final class OperationLock implements AutoCloseable {

	private final String id;
	private final long ownerThreadId;
	private final ExecutionType executionType;
	private final Set<String> paths;
	private final Runnable releaseAction;

	OperationLock(String id, long ownerThreadId, ExecutionType executionType, Set<String> paths,
			Runnable releaseAction) {
		this.id = id;
		this.ownerThreadId = ownerThreadId;
		this.executionType = executionType;
		this.paths = paths;
		this.releaseAction = releaseAction;
	}

	String id() {
		return id;
	}

	ExecutionType executionType() {
		return executionType;
	}

	long ownerThreadId() {
		return ownerThreadId;
	}

	String displayPath() {
		return paths.iterator().next();
	}

	boolean conflictsWith(Set<String> requestedPaths) {
		return paths.stream()
				.anyMatch(current -> requestedPaths.stream().anyMatch(requested -> overlaps(current, requested)));
	}

	/**
	 * Two paths conflict when they are the same, or when either one contains the
	 * other.
	 */
	private boolean overlaps(String first, String second) {
		return first.equals(second) || contains(first, second) || contains(second, first);
	}

	/**
	 * Whether {@code candidate} lives inside {@code ancestor}. The prefix comes
	 * from {@link #asPrefix} rather than from appending a separator, because a
	 * drive root is the one path whose normalised form already ends in one:
	 * {@code D:\} plus a separator is a prefix nothing can match, so a lock held on
	 * a file used to be invisible to a request for the drive containing it - and a
	 * library that sits on a whole drive lost the mutual exclusion this class
	 * exists to provide.
	 */
	private boolean contains(String ancestor, String candidate) {
		return candidate.startsWith(asPrefix(ancestor, "\\")) || candidate.startsWith(asPrefix(ancestor, "/"));
	}

	private String asPrefix(String path, String separator) {
		return path.endsWith(separator) ? path : path + separator;
	}

	@Override
	public void close() {
		releaseAction.run();
	}
}