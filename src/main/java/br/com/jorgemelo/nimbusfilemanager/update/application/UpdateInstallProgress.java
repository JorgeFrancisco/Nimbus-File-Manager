package br.com.jorgemelo.nimbusfilemanager.update.application;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.util.ProgressMath;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.UpdateInstallSnapshot;
import br.com.jorgemelo.nimbusfilemanager.update.domain.enums.UpdatePhase;

/**
 * Thread-safe progress of an update install, written by the runner and read by
 * the screen - the same shape the ffmpeg install already uses, and for the same
 * reason: the download is over a hundred megabytes and a blank wait reads as a
 * hang.
 *
 * <p>
 * The failure message is kept here rather than only flashed on a redirect,
 * because the work outlives the request that started it: whoever asked may have
 * navigated away, and the answer still has to be somewhere when they come back.
 */
@Component
public class UpdateInstallProgress {

	private volatile UpdatePhase phase = UpdatePhase.IDLE;
	private volatile boolean running;
	private volatile long stepStartedAtMillis;
	private volatile long bytesTotal = -1;
	private volatile String message;
	private final AtomicLong bytesDone = new AtomicLong();

	public synchronized void start() {
		phase = UpdatePhase.DOWNLOADING;
		running = true;
		message = null;
		bytesTotal = -1;
		bytesDone.set(0);
		stepStartedAtMillis = System.currentTimeMillis();
	}

	/**
	 * @param totalBytes content length of the installer, or a non-positive value
	 * when the server announces none
	 */
	public synchronized void startDownload(long totalBytes) {
		phase = UpdatePhase.DOWNLOADING;
		bytesTotal = totalBytes > 0 ? totalBytes : -1;
		bytesDone.set(0);
		stepStartedAtMillis = System.currentTimeMillis();
	}

	public void addDownloadedBytes(long bytes) {
		bytesDone.addAndGet(bytes);
	}

	public synchronized void verifying() {
		phase = UpdatePhase.VERIFYING;
	}

	/** The installer is running; this process ends next. */
	public synchronized void starting() {
		phase = UpdatePhase.STARTING;
	}

	/**
	 * @param reason already localized, because the screen only displays it
	 */
	public synchronized void failed(String reason) {
		phase = UpdatePhase.FAILED;
		running = false;
		message = reason;
	}

	public UpdateInstallSnapshot snapshot() {
		UpdatePhase currentPhase = phase;

		long done = bytesDone.get();
		long total = bytesTotal;

		double percent = -1;
		long etaSeconds = -1;

		if (currentPhase == UpdatePhase.DOWNLOADING) {
			percent = ProgressMath.percent(done, total);
			etaSeconds = ProgressMath.etaSeconds(System.currentTimeMillis() - stepStartedAtMillis, done, total);
		}

		return new UpdateInstallSnapshot(currentPhase, running, done, total, percent, etaSeconds, message);
	}
}