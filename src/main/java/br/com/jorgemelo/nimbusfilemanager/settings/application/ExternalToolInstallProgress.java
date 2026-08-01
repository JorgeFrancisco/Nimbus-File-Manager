package br.com.jorgemelo.nimbusfilemanager.settings.application;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.ToolInstallSnapshot;
import br.com.jorgemelo.nimbusfilemanager.settings.domain.enums.ToolInstallPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.util.ProgressMath;

/**
 * Thread-safe progress of the ffmpeg/ffprobe installation, so the settings
 * screen shows a percentage instead of an indeterminate spinner - the download
 * is around 70 MB and a blank wait reads as a hang. Written by the installer,
 * read by the web layer.
 */
@Component
public class ExternalToolInstallProgress {

	private volatile ToolInstallPhase phase = ToolInstallPhase.IDLE;
	private volatile long stepStartedAtMillis;
	private volatile long bytesTotal = -1;
	private final AtomicLong bytesDone = new AtomicLong();

	/** Clears any progress left over from a previous installation. */
	public synchronized void reset() {
		phase = ToolInstallPhase.IDLE;
		bytesTotal = -1;
		bytesDone.set(0);
	}

	/**
	 * @param totalBytes content length of the archive, or a non-positive value when
	 * the server does not announce one.
	 */
	public synchronized void startDownload(long totalBytes) {
		phase = ToolInstallPhase.DOWNLOADING;
		bytesTotal = totalBytes > 0 ? totalBytes : -1;
		bytesDone.set(0);
		stepStartedAtMillis = System.currentTimeMillis();
	}

	public void addDownloadedBytes(long bytes) {
		bytesDone.addAndGet(bytes);
	}

	public synchronized void startExtraction(long totalBytes) {
		phase = ToolInstallPhase.EXTRACTING;
		bytesTotal = totalBytes > 0 ? totalBytes : -1;
		bytesDone.set(0);
		stepStartedAtMillis = System.currentTimeMillis();
	}

	public void addExtractedBytes(long bytes) {
		bytesDone.addAndGet(bytes);
	}

	public ToolInstallSnapshot snapshot() {
		ToolInstallPhase currentPhase = phase;

		long done = bytesDone.get();
		long total = bytesTotal;

		double percent = -1;
		long etaSeconds = -1;

		if (currentPhase != ToolInstallPhase.IDLE) {
			percent = ProgressMath.percent(done, total);
			etaSeconds = ProgressMath.etaSeconds(System.currentTimeMillis() - stepStartedAtMillis, done, total);
		}

		return new ToolInstallSnapshot(currentPhase, done, total, percent, etaSeconds);
	}
}