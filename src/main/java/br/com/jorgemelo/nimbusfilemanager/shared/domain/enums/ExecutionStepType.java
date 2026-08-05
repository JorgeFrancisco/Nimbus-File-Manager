package br.com.jorgemelo.nimbusfilemanager.shared.domain.enums;

public enum ExecutionStepType {

	STARTED, SCANNING_STARTED, PROCESSING_STARTED, PROGRESS_UPDATED, FILE_ERROR, FINISHED, INTERRUPTED, ERROR,
			CANCELLED,

	/** The system decided not to run it - distinct from somebody stopping it. */
	REJECTED
}