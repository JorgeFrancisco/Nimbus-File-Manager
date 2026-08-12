package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.constants.GeoMessages;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.AdminBoundaryKind;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.util.ProgressMath;

/**
 * Where the dataset acquisition reports what it is doing.
 *
 * <p>
 * The run is nine logical stages, always the same nine: acquire each of the
 * three administrative levels, import each of them, complete the territories the
 * main dataset dissolves, publish what was staged, and finish. The global bar
 * counts those stages and nothing else - {@code filesFound} is stages completed,
 * {@code totalExpected} is nine, and the fraction between them is the whole of
 * the first bar.
 *
 * <p>
 * <b>Nine even when some of them cost nothing.</b> A level whose file is already
 * on disk, a level nobody configured, a territory pass with nothing missing:
 * each still announces itself and is still counted. The alternative - a
 * denominator that shrinks with the work - makes the same pipeline read as seven
 * stages on one run and nine on the next, and leaves the person watching to
 * wonder which two failed.
 *
 * <p>
 * <b>The exception is not an installation.</b> A run that finds every level
 * unchanged over a dataset already installed does three conditional requests and
 * stops - see {@link #alreadyUpToDate()}. That is a shorter operation rather than
 * a shorter pipeline, and it reports its own count, because marking an import and
 * a publication done when neither happened would be the exact dishonesty the
 * fixed denominator is here to prevent.
 *
 * <p>
 * <b>The second bar is not part of the first.</b> It measures the item being
 * worked on right now and only where a real denominator exists: bytes against a
 * {@code Content-Length} while downloading, bytes of the file consumed while
 * parsing. A stage with nothing to count clears it rather than showing zero,
 * because zero draws a bar that reads as stuck.
 *
 * <p>
 * The counters that remain are scratch of the run in flight, never the authority
 * on whether one is happening: that is the row's status. One holder is enough
 * because one update runs at a time, which the type's concurrency limit and the
 * geodata path lock both guarantee - and a run nobody attached simply reports
 * nowhere, which is what an import called from a test does.
 */
@Component
public class GeoDatasetProgress {

	/**
	 * Three acquisitions, three imports, territories, publish, finish. Fixed on
	 * purpose: see the class note on why the denominator does not follow the work.
	 */
	private static final int STAGES = 9;

	private final ExecutionProgressService executionProgressService;

	/**
	 * Held in a reference of its own rather than in a plain field: what the
	 * pipeline reads while it works has to be the run the handler attached, and a
	 * volatile field only promises that the write is seen - not that the read and
	 * the null check saw the same one.
	 *
	 * <p>
	 * What is held is the taking rather than the row, because that is what every
	 * report below is written under: a run whose turn ended while it was still
	 * downloading must not go on describing the row that replaced it.
	 */
	private final AtomicReference<ExecutionOwnership> ownership = new AtomicReference<>();

	/**
	 * The stage that most recently announced itself, re-applied when it completes.
	 * Without it the counter would advance under a sentence saying something else:
	 * the write that moves the count also writes the message, and a generic
	 * "progress updated" there would erase the identity a moment after publishing
	 * it.
	 */
	private final AtomicReference<ExecutionMessage> stageMessage = new AtomicReference<>();

	private volatile long bytesTotal = -1;
	private final AtomicLong bytesDone = new AtomicLong();
	private final AtomicLong recordsImported = new AtomicLong();
	private final AtomicLong stagesDone = new AtomicLong();

	public GeoDatasetProgress(ExecutionProgressService executionProgressService) {
		this.executionProgressService = executionProgressService;
	}

	/**
	 * Points the pipeline's reports at the execution being run, and clears what a
	 * previous one left.
	 */
	public synchronized void attach(ExecutionOwnership ownership) {
		this.ownership.set(ownership);

		bytesTotal = -1;
		bytesDone.set(0);
		recordsImported.set(0);
		stagesDone.set(0);
		stageMessage.set(null);

		executionProgressService.updateTotal(ownership, STAGES);
	}

	public synchronized void detach() {
		ownership.set(null);
	}

	/** How many boundary records the attached run has imported so far. */
	public long recordsImported() {
		return recordsImported.get();
	}

	/**
	 * How many of the nine the run has behind it. Read by the handler when it
	 * closes the row, so the finished execution says nine of nine rather than a
	 * number that means something else.
	 */
	public int stagesDone() {
		return (int) stagesDone.get();
	}

	/**
	 * @param totalBytes content length of the file, or a non-positive value when
	 * unknown - in which case the second bar stays away instead of guessing.
	 */
	public synchronized void downloading(AdminBoundaryKind kind, long totalBytes) {
		stageStarted(ExecutionPhase.SCANNING, GeoMessages.downloading(kind), totalBytes);
	}

	/** The file was already on disk, so the acquisition stage has nothing to do. */
	public synchronized void alreadyAvailable(AdminBoundaryKind kind) {
		stageStarted(ExecutionPhase.SCANNING, GeoMessages.alreadyAvailable(kind), -1);
	}

	/** A level nobody configured still occupies its place in the sequence. */
	public synchronized void levelNotConfigured(AdminBoundaryKind kind) {
		stageStarted(ExecutionPhase.SCANNING, GeoMessages.levelNotConfigured(kind), -1);
	}

	/**
	 * @param totalBytes size of the file being imported, or non-positive when
	 * unknown. Bytes consumed by the parser stand in for features, because the
	 * feature count is unknown without a full pre-scan.
	 */
	public synchronized void importing(AdminBoundaryKind kind, long totalBytes) {
		stageStarted(ExecutionPhase.PROCESSING, GeoMessages.importing(kind), totalBytes);
	}

	/** The import stage of a level that never arrived. */
	public synchronized void nothingToImport(AdminBoundaryKind kind) {
		stageStarted(ExecutionPhase.PROCESSING, GeoMessages.nothingToImport(kind), -1);
	}

	public synchronized void completingTerritories() {
		stageStarted(ExecutionPhase.PROCESSING, GeoMessages.completingTerritories(), -1);
	}

	/** Turned off, or nothing was missing. Either way the stage is behind us. */
	public synchronized void noTerritoriesMissing() {
		stageStarted(ExecutionPhase.PROCESSING, GeoMessages.noTerritoriesMissing(), -1);
	}

	/**
	 * The run turned out to be a verification: every level came back unchanged and
	 * what is installed is complete, so the six stages an installation would still
	 * have are not going to happen.
	 *
	 * <p>
	 * <b>The denominator shrinks here, and only here.</b> Everywhere else it is
	 * fixed at {@link #STAGES} precisely so a pipeline cannot look shorter on one
	 * run than another - but this is not that pipeline running fast, it is a
	 * different and much shorter operation: three conditional requests and the
	 * answer. Reporting nine stages and marking six of them done would draw a full
	 * bar over an import and a publication that never occurred, which is the one
	 * thing the fixed denominator exists to prevent.
	 */
	public synchronized void alreadyUpToDate() {
		ExecutionOwnership current = ownership.get();

		if (current == null) {
			return;
		}

		executionProgressService.updateTotal(current, (int) stagesDone.get() + 1);

		stageStarted(ExecutionPhase.PROCESSING, GeoMessages.alreadyUpToDate(), -1);

		stageFinished();
	}

	public synchronized void publishing() {
		stageStarted(ExecutionPhase.PROCESSING, GeoMessages.publishing(), -1);
	}

	public synchronized void finishing() {
		stageStarted(ExecutionPhase.PROCESSING, GeoMessages.finishing(), -1);
	}

	public void addDownloadedBytes(long bytes) {
		report(bytes);
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
	 * One of the nine is behind us.
	 *
	 * <p>
	 * Called only after the stage's work succeeded, which is what keeps the count
	 * honest when something fails: the row goes on naming the stage it stopped in,
	 * and that stage is not among the ones counted. The message written here is the
	 * one the stage announced, so the sentence and the number always describe the
	 * same stage.
	 */
	public synchronized void stageFinished() {
		ExecutionOwnership current = ownership.get();

		if (current == null) {
			return;
		}

		int done = (int) stagesDone.incrementAndGet();

		executionProgressService.updateLiveProgress(current, done, done, 0, 0, stageMessage.get());
	}

	private void stageStarted(ExecutionPhase phase, ExecutionMessage message, long totalBytes) {
		bytesTotal = totalBytes > 0 ? totalBytes : -1;
		bytesDone.set(0);

		stageMessage.set(message);

		ExecutionOwnership current = ownership.get();

		if (current == null) {
			return;
		}

		executionProgressService.updatePhase(current, phase, ExecutionStepType.PROGRESS_UPDATED, message);

		if (bytesTotal > 0) {
			executionProgressService.startsCurrentItem(current);
		} else {
			executionProgressService.clearsCurrentItem(current);
		}
	}

	/**
	 * The second level of the bar: how far into the file being downloaded or parsed
	 * right now. The row's own throttle decides how often that reaches the
	 * database, which is why this can be called for every buffer read.
	 */
	private void report(long bytes) {
		long done = bytesDone.addAndGet(bytes);

		ExecutionOwnership current = ownership.get();

		if (current == null || bytesTotal <= 0) {
			return;
		}

		executionProgressService.updateCurrentItem(current, (int) ProgressMath.percent(done, bytesTotal));
	}
}