package br.com.jorgemelo.nimbusfilemanager.backup.application.dto;

import br.com.jorgemelo.nimbusfilemanager.backup.domain.enums.BackupPhase;

/**
 * What the screen shows while a backup or a restore runs.
 *
 * <p>
 * Progress is counted in tables rather than in bytes: the size of a table is
 * only known once it has been streamed, and a percentage that jumps from 0 to
 * 100 at the end is worse than none. The table being worked on is carried too,
 * because with a handful of large tables the count alone barely moves.
 *
 * @param phase what is happening now
 * @param table the table being read or written, or {@code null} between tables
 * @param tablesDone how many finished
 * @param tablesTotal how many there are
 * @param percent 0-100, or negative when there is nothing to divide by
 */
public record BackupSnapshot(BackupPhase phase, String table, int tablesDone, int tablesTotal, double percent) {

	public boolean exporting() {
		return phase == BackupPhase.EXPORTING;
	}

	public boolean clearing() {
		return phase == BackupPhase.CLEARING;
	}

	public boolean importing() {
		return phase == BackupPhase.IMPORTING;
	}
}