package br.com.jorgemelo.nimbusfilemanager.backup.application;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.BackupSnapshot;
import br.com.jorgemelo.nimbusfilemanager.backup.domain.enums.BackupPhase;

/**
 * Thread-safe progress of a backup or a restore, written by the service and
 * read by the web layer.
 *
 * <p>
 * It exists because the first real backup took about a minute on a catalog of a
 * few hundred MB with nothing on screen, which reads as a hang. The restore
 * needs it more: it empties the catalog before loading the archive, so the
 * silent stretch is exactly the one where closing the browser does damage.
 */
@Component
public class BackupProgress {

	private volatile BackupPhase phase = BackupPhase.IDLE;
	private volatile String table;
	private volatile int tablesDone;
	private volatile int tablesTotal;

	/** Clears whatever a previous run left behind. */
	public synchronized void reset() {
		phase = BackupPhase.IDLE;
		table = null;
		tablesDone = 0;
		tablesTotal = 0;
	}

	public synchronized void start(BackupPhase startingPhase, int total) {
		phase = startingPhase;
		table = null;
		tablesDone = 0;
		tablesTotal = total;
	}

	/** The table about to be read or written. */
	public synchronized void startTable(String name) {
		table = name;
	}

	public synchronized void finishTable() {
		tablesDone++;
		table = null;
	}

	public BackupSnapshot snapshot() {
		BackupPhase currentPhase = phase;

		int done = tablesDone;
		int total = tablesTotal;

		double percent = total > 0 ? Math.min(100.0, done * 100.0 / total) : -1;

		return new BackupSnapshot(currentPhase, table, done, total, currentPhase == BackupPhase.IDLE ? -1 : percent);
	}
}