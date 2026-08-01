package br.com.jorgemelo.nimbusfilemanager.backup.application.dto;

import br.com.jorgemelo.nimbusfilemanager.backup.domain.enums.BackupPhase;

/**
 * What the screen shows while a backup or a restore runs.
 *
 * <p>
 * There is no percentage, and deliberately so: the dump is one opaque command,
 * and its final size is not known until it finishes. What can be reported
 * honestly is how much has been written so far, which moves steadily and tells
 * a waiting operator that something is happening. An earlier version counted
 * tables because the format was one file per table; after the change there was
 * a single step left, and the counter kept reading "0 of 1" while showing the
 * schema version where a table name belonged.
 *
 * @param phase what is happening now
 * @param bytes how much of the file exists so far
 */
public record BackupSnapshot(BackupPhase phase, long bytes) {

	public boolean exporting() {
		return phase == BackupPhase.EXPORTING;
	}

	public boolean importing() {
		return phase == BackupPhase.IMPORTING;
	}

	/** Megabytes, which is the unit a person reads without counting zeros. */
	public long megabytes() {
		return bytes / 1048576;
	}
}