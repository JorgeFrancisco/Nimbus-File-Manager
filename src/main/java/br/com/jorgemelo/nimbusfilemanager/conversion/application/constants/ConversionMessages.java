package br.com.jorgemelo.nimbusfilemanager.conversion.application.constants;

import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;

/**
 * The status a conversion writes on its execution, as a key and its arguments.
 *
 * <p>
 * Never as resolved text. The work happens in the worker now, which has no
 * request behind it and therefore no language: text built there would be in
 * whatever locale that process defaults to, for a screen the user opened in
 * another. Kept as a code, it is localized on read, where the request is.
 */
public final class ConversionMessages {

	public static ExecutionMessage started(int total) {
		return of("backend.conversion.started", total);
	}

	public static ExecutionMessage completed(int converted, int skipped, int errors, String saved) {
		return of("backend.conversion.completed", converted, skipped, errors, saved);
	}

	public static ExecutionMessage cancelledBatch(int converted, int errors) {
		return of("backend.conversion.cancelledBatch", converted, errors);
	}

	public static ExecutionMessage failed(String detail) {
		return of("backend.execution.operationFailed", detail);
	}


	private static ExecutionMessage of(String code, Object... args) {
		return new ExecutionMessage(code, List.of(args));
	}

	private ConversionMessages() {
	}
}