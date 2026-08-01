package br.com.jorgemelo.nimbusfilemanager.backup.domain.enums;

/**
 * What a running backup operation is doing. The restore phases are told apart
 * because they carry very different weight on screen: emptying the catalog is
 * the point of no return, and a screen that goes quiet there is the one a user
 * is most likely to close.
 */
public enum BackupPhase {

	IDLE,

	/** Reading the tables out into the archive. */
	EXPORTING,

	/** Emptying the catalog before loading the archive back. */
	CLEARING,

	/** Loading the archive back into the tables. */
	IMPORTING
}