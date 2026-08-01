package br.com.jorgemelo.nimbusfilemanager.backup.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.BackupSnapshot;
import br.com.jorgemelo.nimbusfilemanager.backup.domain.enums.BackupPhase;
import lombok.extern.slf4j.Slf4j;

/**
 * Thread-safe progress of a backup or a restore, written by the service and
 * read by the web layer.
 *
 * <p>
 * It exists because the first real backup took about a minute on a catalog of a
 * few hundred MB with nothing on screen, which reads as a hang. The restore
 * needs it more: it replaces the catalog, so the silent stretch is the one
 * where closing the browser does damage.
 *
 * <p>
 * The size is read from the file at the moment the screen asks, rather than
 * pushed by whoever is writing it: the work happens inside one external command
 * that reports nothing until it exits, so the file growing on disk is the only
 * honest signal available.
 */
@Slf4j
@Component
public class BackupProgress {

	private volatile BackupPhase phase = BackupPhase.IDLE;
	private final AtomicReference<Path> file = new AtomicReference<>();

	/** Clears whatever a previous run left behind. */
	public synchronized void reset() {
		phase = BackupPhase.IDLE;
		file.set(null);
	}

	/**
	 * @param growing the file being written or read, watched for its size
	 */
	public synchronized void start(BackupPhase startingPhase, Path growing) {
		phase = startingPhase;
		file.set(growing);
	}

	public BackupSnapshot snapshot() {
		return new BackupSnapshot(phase, size());
	}

	private long size() {
		Path current = file.get();

		if (current == null) {
			return 0;
		}

		try {
			return Files.exists(current) ? Files.size(current) : 0;
		} catch (IOException exception) {
			log.debug("Could not measure {}", current, exception);

			return 0;
		}
	}
}