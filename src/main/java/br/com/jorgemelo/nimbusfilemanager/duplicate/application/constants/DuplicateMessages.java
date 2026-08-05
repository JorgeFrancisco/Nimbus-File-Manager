package br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants;

import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;

/**
 * The status a duplicate deletion writes on its execution, as a key and its
 * arguments.
 *
 * <p>
 * Never as resolved text. The work happens in the worker now, which has no
 * request behind it and therefore no language: text built there would be in
 * whatever locale that process defaults to, for a screen the user opened in
 * another. Kept as a code, it is localized on read, where the request is.
 */
public final class DuplicateMessages {

	public static ExecutionMessage deletionStarted() {
		return of("backend.duplicates.deletionStarted");
	}

	public static ExecutionMessage deletionCompleted(int moved, int skipped, int errors) {
		return of("backend.duplicates.deletionCompleted", moved, skipped, errors);
	}

	public static ExecutionMessage failed(String detail) {
		return of("backend.execution.operationFailed", detail);
	}


	private static ExecutionMessage of(String code, Object... args) {
		return new ExecutionMessage(code, List.of(args));
	}

	private DuplicateMessages() {
	}
}