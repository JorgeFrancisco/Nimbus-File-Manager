package br.com.jorgemelo.nimbusfilemanager.catalog.application.constants;

import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;

/**
 * What the catalog retention purge writes on its execution, as a key and its
 * arguments.
 *
 * <p>
 * Never as resolved text: the purge runs in the worker, which has no request
 * behind it and therefore no language.
 */
public final class CatalogMessages {

	private CatalogMessages() {
	}

	public static ExecutionMessage purgeStarted() {
		return of("backend.catalog.purgeStarted");
	}

	public static ExecutionMessage purgeCompleted(int removed) {
		return of("backend.catalog.purgeCompleted", removed);
	}

	/**
	 * The window was read again when the pass ran and had been turned off, or the
	 * rows it was queued for had already gone. Said in words because a row
	 * reporting "0" and a row reporting "nothing to do" are answers to different
	 * questions.
	 */
	public static ExecutionMessage purgeFoundNothing() {
		return of("backend.catalog.purgeFoundNothing");
	}

	private static ExecutionMessage of(String code, Object... args) {
		return new ExecutionMessage(code, List.of(args));
	}
}