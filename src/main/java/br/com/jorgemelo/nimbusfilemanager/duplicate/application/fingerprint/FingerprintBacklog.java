package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import java.util.function.BooleanSupplier;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DrainResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;

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

	long rebuild();

	DrainResult drainPending(BooleanSupplier stop, ProgressListener progress);
}