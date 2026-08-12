package br.com.jorgemelo.nimbusfilemanager.execution.application;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;

/**
 * The number PostgreSQL takes an advisory lock on while one request is being
 * admitted to the queue.
 *
 * <p>
 * The type belongs in the identity, not only the key: an inventory of
 * {@code d:\fotos} and a reconcile of {@code d:\fotos} carry the same
 * deduplication key and are not the same intention, so leaving the type out
 * would make the two wait for each other over nothing.
 *
 * <p>
 * The hash is {@link OperationPathKey#key}, because what is needed here is the
 * property proven there: one string becomes one number, in every process and
 * every JVM version. A collision costs two unrelated admissions a turn each and
 * nothing more - it never means the two requests are equivalent, which the
 * unique indexes decide and they alone.
 */
public final class QueueAdmissionKey {

	private QueueAdmissionKey() {
	}

	public static long of(ExecutionType executionType, String dedupKey) {
		return OperationPathKey.key(executionType.name() + ":" + dedupKey);
	}
}