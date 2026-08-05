package br.com.jorgemelo.nimbusfilemanager.geolocation.application.constants;

import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;

/**
 * What a location rebuild and a dataset update write on their executions, as a
 * key and its arguments.
 *
 * <p>
 * Never as resolved text: both run in the worker, which has no request behind it
 * and therefore no language.
 */
public final class GeoMessages {

	public static final int PAYLOAD_SCHEMA_VERSION = 1;

	public static final String REBUILD_QUEUED = "backend.geodata.rebuildQueued";
	public static final String UPDATE_QUEUED = "backend.geodata.updateQueued";

	public static ExecutionMessage rebuilding() {
		return of(REBUILD_QUEUED);
	}

	public static ExecutionMessage rebuilt(long candidates, long resolved, long unresolved, long errors) {
		return of("backend.geodata.rebuildCompleted", candidates, resolved, unresolved, errors);
	}

	/**
	 * The step, as the level being worked on rather than as a byte count: the
	 * numbers live on the row, and what the sentence adds is which of the three
	 * administrative levels the run is on.
	 */
	public static ExecutionMessage downloading(String levelKey) {
		return of("backend.geodata.downloading", levelKey);
	}

	public static ExecutionMessage importing(String levelKey) {
		return of("backend.geodata.importing", levelKey);
	}

	public static ExecutionMessage updated(long records) {
		return of("backend.geodata.updateCompleted", records);
	}

	/**
	 * Stopped before the acquisition began, which is the only point at which it
	 * can be stopped: once files are being staged and rows imported, the protocol
	 * that keeps the previous dataset intact is the one that finishes or fails,
	 * and there is no third answer it knows how to give.
	 */
	public static ExecutionMessage updateCancelled() {
		return of("backend.geodata.updateCancelled");
	}

	/**
	 * An inventory reads locations while it runs, so replacing the dataset - or
	 * rewriting the answers - under it would change what it is cataloguing halfway
	 * through.
	 */
	public static ExecutionMessage deferred() {
		return of("backend.geodata.deferred");
	}

	private static ExecutionMessage of(String code, Object... args) {
		return new ExecutionMessage(code, List.of(args));
	}

	private GeoMessages() {
	}
}