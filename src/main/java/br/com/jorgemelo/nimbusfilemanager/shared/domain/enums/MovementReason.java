package br.com.jorgemelo.nimbusfilemanager.shared.domain.enums;

public enum MovementReason {

	NONE,

	TARGET_EXISTS, DUPLICATE_TARGET, OVERWRITE_DISABLED, DUPLICATE_QUARANTINED, CONVERTED_QUARANTINED,

	SOURCE_NOT_FOUND, SOURCE_NOT_PHYSICAL, ALREADY_MOVED, ACCESS_DENIED, IO_ERROR, DATABASE_UPDATE_FAILED,

	INTEGRITY_CHECK_FAILED,

	/** A file the user sent to quarantine from the file explorer. */
	USER_QUARANTINED,

	/** The reverse move an undo writes, so the trail reads in both directions. */
	UNDONE_BY_USER,

	/** The reverse move a quarantine restore writes, for the same reason. */
	RESTORED_FROM_QUARANTINE,

	USER_CANCELLED
}