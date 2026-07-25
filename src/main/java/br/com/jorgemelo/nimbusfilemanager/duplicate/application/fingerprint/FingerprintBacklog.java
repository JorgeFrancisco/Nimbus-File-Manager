package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import java.util.function.BooleanSupplier;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DrainResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;

/**
 * A drainable fingerprint backlog (photo or video), as seen by the neutral
 * {@link FingerprintJobRunner}. It is the small surface the async runner needs
 * - identify the job, read its status, yield to inventory, drain it and rebuild
 * it - so the runner never knows which media it is driving.
 */
interface FingerprintBacklog {

	FingerprintKind kind();

	String algorithm();

	boolean inventoryActive();

	FingerprintBacklogStatus status();

	long rebuild();

	DrainResult drainPending(BooleanSupplier stop, ProgressListener progress);
}