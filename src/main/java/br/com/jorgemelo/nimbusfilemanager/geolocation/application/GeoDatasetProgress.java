package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.constants.GeoMessages;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.AdminBoundaryKind;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.util.ProgressMath;

/**
 * Where the dataset acquisition reports what it is doing.
 *
 * <p>
 * It used to <em>be</em> the answer: a singleton the pipeline wrote to and the
 * settings page read from, which worked only while both happened in the same
 * process. Now it writes through to the execution row, and nothing reads it -
 * so the screen asks the database, like every other progress in the product.
 *
 * <p>
 * The counters that remain are scratch of the run in flight (bytes of the
 * current step, records so far), never the authority on whether one is
 * happening: that is the row's status. One holder is enough because one update
 * runs at a time, which the type's concurrency limit and the geodata path lock
 * both guarantee - and a run nobody attached simply reports nowhere, which is
 * what an import called from a test does.
 */
@Component
public class GeoDatasetProgress {

	/** Three levels: a country file, a state file and a municipality file. */
	private static final int LEVELS = AdminBoundaryKind.values().length;

	private final ExecutionProgressService executionProgressService;

	/**
	 * Held in a reference of its own rather than in a plain field: what the
	 * pipeline reads while it works has to be the run the handler attached, and a
	 * volatile field only promises that the write is seen - not that the read and
	 * the null check saw the same one.
	 */
	private final AtomicReference<Execution> execution = new AtomicReference<>();

	private volatile long bytesTotal = -1;
	private final AtomicLong bytesDone = new AtomicLong();
	private final AtomicLong recordsImported = new AtomicLong();
	private final AtomicLong levelsDone = new AtomicLong();

	public GeoDatasetProgress(ExecutionProgressService executionProgressService) {
		this.executionProgressService = executionProgressService;
	}

	/**
	 * Points the pipeline's reports at the execution being run, and clears what a
	 * previous one left.
	 */
	public synchronized void attach(Execution execution) {
		this.execution.set(execution);

		bytesTotal = -1;
		bytesDone.set(0);
		recordsImported.set(0);
		levelsDone.set(0);

		executionProgressService.updateTotal(execution, LEVELS);
	}

	public synchronized void detach() {
		execution.set(null);
	}

	/** How many boundary records the attached run has imported so far. */
	public long recordsImported() {
		return recordsImported.get();
	}

	/**
	 * @param totalBytes content length of the file, or a non-positive value when
	 * unknown.
	 */
	public synchronized void startDownload(AdminBoundaryKind kind, long totalBytes) {
		startStep(ExecutionPhase.SCANNING, GeoMessages.downloading(label(kind)), totalBytes);
	}

	public void addDownloadedBytes(long bytes) {
		report(bytes);
	}

	/**
	 * @param totalBytes size of the file being imported, or non-positive when
	 * unknown.
	 */
	public synchronized void startImport(AdminBoundaryKind kind, long totalBytes) {
		startStep(ExecutionPhase.PROCESSING, GeoMessages.importing(label(kind)), totalBytes);
	}

	/**
	 * Bytes of the source file consumed by the import parser (percentage proxy).
	 */
	public void addImportedBytes(long bytes) {
		report(bytes);
	}

	public void addImportedRecords(long records) {
		recordsImported.addAndGet(records);
	}

	/**
	 * A level finished. Counted as the run's items, which is what makes the
	 * executions screen show "2 of 3" for something whose unit is neither files nor
	 * bytes.
	 */
	public synchronized void levelFinished() {
		Execution current = execution.get();

		if (current == null) {
			return;
		}

		executionProgressService.updateLiveProgress(current, LEVELS, (int) levelsDone.incrementAndGet(), 0, 0,
				ExecutionMessages.progressUpdated());
	}

	private void startStep(ExecutionPhase phase, ExecutionMessage message, long totalBytes) {
		bytesTotal = totalBytes > 0 ? totalBytes : -1;
		bytesDone.set(0);

		Execution current = execution.get();

		if (current == null) {
			return;
		}

		executionProgressService.updatePhase(current, phase, ExecutionStepType.PROGRESS_UPDATED, message);
		executionProgressService.startsCurrentItem(current);
	}

	/**
	 * The second level of the bar: how far into the file being downloaded or parsed
	 * right now. The row's own throttle decides how often that reaches the
	 * database, which is why this can be called for every buffer read.
	 */
	private void report(long bytes) {
		long done = bytesDone.addAndGet(bytes);

		Execution current = execution.get();

		if (current == null || bytesTotal <= 0) {
			return;
		}

		executionProgressService.updateCurrentItem(current, (int) ProgressMath.percent(done, bytesTotal));
	}

	/**
	 * Stable i18n key for the administrative level being processed, resolved when
	 * the message is read. Kept as a key, not text, so the wording lives in the
	 * bundles and a run has no language of its own.
	 */
	private static String label(AdminBoundaryKind kind) {
		return switch (kind) {
		case COUNTRY -> "settings.geo.step.country";
		case STATE -> "settings.geo.step.state";
		case MUNICIPALITY -> "settings.geo.step.municipality";
		};
	}
}