package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import java.util.OptionalLong;
import java.util.function.BooleanSupplier;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DrainResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionMetricsContext;

/**
 * A drainable fingerprint backlog, photo or video, as the handler that drains it
 * sees one. The surface is small on purpose - identify the job, read its status,
 * yield to an inventory, drain it, rebuild it - so the handler never knows which
 * media it is driving.
 */
interface FingerprintBacklog {

	FingerprintKind kind();

	String algorithm();

	boolean pausedByActiveExecution();

	FingerprintBacklogStatus status();

	/**
	 * @return how many fingerprints were discarded, or empty when the taking is
	 * over and nothing was discarded - in which case what was derived from them
	 * must not be discarded either
	 */
	OptionalLong seedRebuild(ExecutionOwnership ownership);

	boolean rebuildIsOpen();

	/**
	 * @param metricsContext the caller's own accumulators. Photo and video drain
	 * at the same time through one pool and one gate, so the run that asked for
	 * this drain is the one the cost is counted against.
	 */
	DrainResult drainPending(BooleanSupplier stop, ProgressListener progress, ExecutionOwnership ownership,
			ExecutionMetricsContext metricsContext);
}