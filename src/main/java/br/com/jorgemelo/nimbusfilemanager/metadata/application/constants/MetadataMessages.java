package br.com.jorgemelo.nimbusfilemanager.metadata.application.constants;

import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;

/**
 * What a metadata rebuild writes on its execution, as a key and its arguments.
 *
 * <p>
 * Never as resolved text: the rebuild runs in the worker, which has no request
 * behind it and therefore no language.
 */
public final class MetadataMessages {

	public static final int PAYLOAD_SCHEMA_VERSION = 1;

	public static final String STARTED = "backend.metadata.rebuildStarted";

	public static ExecutionMessage rebuilding() {
		return of(STARTED);
	}

	public static ExecutionMessage completed(long candidates, long rebuilt, long missing, long errors) {
		return of("backend.metadata.rebuildCompleted", candidates, rebuilt, missing, errors);
	}

	/** A dry run writes nothing, so what it says is what it would have done. */
	public static ExecutionMessage simulated(long candidates, long wouldChange) {
		return of("backend.metadata.rebuildSimulated", candidates, wouldChange);
	}

	/**
	 * An inventory is cataloguing the very files this would re-read, so a pass
	 * started beside it would rebuild from a folder that is still changing.
	 */
	public static ExecutionMessage deferred() {
		return of("backend.metadata.rebuildDeferred");
	}

	private static ExecutionMessage of(String code, Object... args) {
		return new ExecutionMessage(code, List.of(args));
	}

	private MetadataMessages() {
	}
}