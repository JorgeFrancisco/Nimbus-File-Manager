package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.Snapshot;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.AdminBoundaryKind;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.Phase;
import br.com.jorgemelo.nimbusfilemanager.shared.util.ProgressMath;

/**
 * Thread-safe progress of the geographic dataset download/import, so the admin
 * screen can show a percentage and time estimate instead of an indeterminate
 * spinner. Written by the acquisition/import pipeline, read by the web layer.
 * Technology-neutral: it knows administrative levels, never a concrete source.
 */
@Component
public class GeoDatasetProgress {

	private volatile Phase phase = Phase.IDLE;
	private volatile String stepLabel = "";
	private volatile long stepStartedAtMillis;
	private volatile long bytesTotal = -1;
	private final AtomicLong bytesDone = new AtomicLong();
	private final AtomicLong recordsImported = new AtomicLong();

	/** Clears any progress left over from a previous operation. */
	public synchronized void reset() {
		phase = Phase.IDLE;
		stepLabel = "";
		bytesTotal = -1;
		bytesDone.set(0);
		recordsImported.set(0);
	}

	/**
	 * @param totalBytes content length of the file, or a non-positive value when
	 * unknown.
	 */
	public synchronized void startDownload(AdminBoundaryKind kind, long totalBytes) {
		phase = Phase.DOWNLOADING;
		stepLabel = label(kind);
		bytesTotal = totalBytes > 0 ? totalBytes : -1;
		bytesDone.set(0);
		stepStartedAtMillis = System.currentTimeMillis();
	}

	public void addDownloadedBytes(long bytes) {
		bytesDone.addAndGet(bytes);
	}

	/**
	 * @param totalBytes size of the file being imported, or non-positive when
	 * unknown.
	 */
	public synchronized void startImport(AdminBoundaryKind kind, long totalBytes) {
		phase = Phase.IMPORTING;
		stepLabel = label(kind);
		bytesTotal = totalBytes > 0 ? totalBytes : -1;
		bytesDone.set(0);
		stepStartedAtMillis = System.currentTimeMillis();
	}

	/**
	 * Bytes of the source file consumed by the import parser (percentage proxy).
	 */
	public void addImportedBytes(long bytes) {
		bytesDone.addAndGet(bytes);
	}

	public void addImportedRecords(long records) {
		recordsImported.addAndGet(records);
	}

	public Snapshot snapshot() {
		Phase currentPhase = phase;

		long done = bytesDone.get();
		long total = bytesTotal;

		double percent = -1;
		long etaSeconds = -1;

		// Average rate since the current step started; stable enough for a
		// panel that refreshes every few seconds. Works for both phases: the
		// import tracks bytes of the source file consumed by the parser.
		if (currentPhase != Phase.IDLE) {
			percent = ProgressMath.percent(done, total);
			etaSeconds = ProgressMath.etaSeconds(System.currentTimeMillis() - stepStartedAtMillis, done, total);
		}

		return new Snapshot(currentPhase, stepLabel, done, total, percent, etaSeconds, recordsImported.get());
	}

	/**
	 * Stable i18n key for the administrative level being processed, resolved by the
	 * settings screen ({@code #messages.msg}). Kept as a key, not text, so the
	 * label lives in the bundles and this component stays free of user-facing
	 * strings.
	 */
	private static String label(AdminBoundaryKind kind) {
		return switch (kind) {
		case COUNTRY -> "settings.geo.step.country";
		case STATE -> "settings.geo.step.state";
		case MUNICIPALITY -> "settings.geo.step.municipality";
		};
	}
}