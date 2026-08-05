package br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants;

import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;

/**
 * What a fingerprint backlog writes on its execution, as a key and its
 * arguments.
 *
 * <p>
 * Never as resolved text: the drain runs in the worker, which has no request
 * behind it and therefore no language.
 */
public final class FingerprintMessages {

	public static ExecutionMessage started(long pending) {
		return of("backend.duplicates.fingerprintStarted", pending);
	}

	public static ExecutionMessage completed(long processed, long failed) {
		return of("backend.duplicates.fingerprintCompleted", processed, failed);
	}

	/**
	 * Stepping aside is not a failure and the row says which: an inventory or a
	 * conversion is holding what this needs, and the next one is asked for when
	 * that ends.
	 */
	public static ExecutionMessage deferred() {
		return of("backend.duplicates.fingerprintDeferred");
	}

	private static ExecutionMessage of(String code, Object... args) {
		return new ExecutionMessage(code, List.of(args));
	}

	private FingerprintMessages() {
	}
}