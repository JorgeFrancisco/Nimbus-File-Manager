package br.com.jorgemelo.nimbusfilemanager.settings.application.constants;

import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;

/**
 * What a settings command writes on its execution, as a key and its arguments.
 *
 * <p>
 * Never as resolved text: the switch runs in the worker, which has no request
 * behind it and therefore no language.
 */
public final class SettingsMessages {

	/** What a queued switch says about itself until a worker claims it. */
	public static final String LIBRARY_SWITCH_STARTED = "backend.settings.librarySwitchStarted";

	/**
	 * How many catalogued files stopped being the collection. It is the number
	 * worth reporting because it is the one a person can check against what they
	 * remember having in the library they replaced.
	 */
	public static ExecutionMessage librarySwitched(int removed) {
		return of("backend.settings.librarySwitched", removed);
	}

	private static ExecutionMessage of(String code, Object... args) {
		return new ExecutionMessage(code, List.of(args));
	}

	private SettingsMessages() {
	}
}