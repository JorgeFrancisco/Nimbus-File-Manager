package br.com.jorgemelo.nimbusfilemanager.backup.domain.enums;

/**
 * What a running backup operation is doing. Emptying the catalog is not among
 * them: the restore hands that to the dump tool, which drops and recreates each
 * object as it goes, so there is no separate step to report.
 */
public enum BackupPhase {

	IDLE,

	/** Reading the tables out into the archive. */
	EXPORTING,


	/** Loading the archive back into the tables. */
	IMPORTING
}