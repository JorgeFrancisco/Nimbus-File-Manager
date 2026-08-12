package br.com.jorgemelo.nimbusfilemanager.geolocation.application.constants;

import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.AdminBoundaryKind;

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
	 * The stage, as the level being worked on rather than as a byte count: the
	 * numbers live on the row, and what the sentence adds is which of the nine
	 * stages the run is on.
	 *
	 * <p>
	 * The level is part of the <em>code</em> and not an argument. It used to be an
	 * argument carrying the key of another message, on the belief that the read
	 * side would resolve it - and nothing does: {@code MessageSource} resolves an
	 * argument only when it is a {@code MessageSourceResolvable}, so a plain string
	 * reaches {@code MessageFormat} verbatim and the screen read "Baixando
	 * settings.geo.step.country.". One code per level costs three lines a bundle
	 * and needs no resolution that does not exist.
	 *
	 * <p>
	 * Written out whole rather than assembled from a prefix, because the build
	 * reads these literals: {@code BackendMessageKeysTest} scans the source for
	 * {@code backend.*} keys and fails when one is missing from a bundle, and a key
	 * built by concatenation is a key it cannot see. Five methods of three lines buy
	 * back that guarantee.
	 */
	public static ExecutionMessage downloading(AdminBoundaryKind kind) {
		return of(switch (kind) {
		case COUNTRY -> "backend.geodata.downloading.country";
		case STATE -> "backend.geodata.downloading.state";
		case MUNICIPALITY -> "backend.geodata.downloading.municipality";
		});
	}

	/**
	 * The acquisition stage of a level whose file was already on disk. The stage
	 * happened - there was simply nothing left for it to do - and saying so is what
	 * keeps the nine-stage sequence readable when one of them costs no time.
	 */
	public static ExecutionMessage alreadyAvailable(AdminBoundaryKind kind) {
		return of(switch (kind) {
		case COUNTRY -> "backend.geodata.alreadyAvailable.country";
		case STATE -> "backend.geodata.alreadyAvailable.state";
		case MUNICIPALITY -> "backend.geodata.alreadyAvailable.municipality";
		});
	}

	/** A level nobody configured: not an error, and not work either. */
	public static ExecutionMessage levelNotConfigured(AdminBoundaryKind kind) {
		return of(switch (kind) {
		case COUNTRY -> "backend.geodata.levelNotConfigured.country";
		case STATE -> "backend.geodata.levelNotConfigured.state";
		case MUNICIPALITY -> "backend.geodata.levelNotConfigured.municipality";
		});
	}

	public static ExecutionMessage importing(AdminBoundaryKind kind) {
		return of(switch (kind) {
		case COUNTRY -> "backend.geodata.importing.country";
		case STATE -> "backend.geodata.importing.state";
		case MUNICIPALITY -> "backend.geodata.importing.municipality";
		});
	}

	/** The import stage of a level that never arrived, for the same reason. */
	public static ExecutionMessage nothingToImport(AdminBoundaryKind kind) {
		return of(switch (kind) {
		case COUNTRY -> "backend.geodata.nothingToImport.country";
		case STATE -> "backend.geodata.nothingToImport.state";
		case MUNICIPALITY -> "backend.geodata.nothingToImport.municipality";
		});
	}

	public static ExecutionMessage completingTerritories() {
		return of("backend.geodata.completingTerritories");
	}

	/**
	 * Turned off, or every ISO country already has a polygon of its own. Both are
	 * ordinary, and both leave the seventh stage with nothing to do.
	 */
	public static ExecutionMessage noTerritoriesMissing() {
		return of("backend.geodata.noTerritoriesMissing");
	}

	public static ExecutionMessage publishing() {
		return of("backend.geodata.publishing");
	}

	public static ExecutionMessage finishing() {
		return of("backend.geodata.finishing");
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