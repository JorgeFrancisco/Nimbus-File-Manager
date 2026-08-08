package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ItemProgressWrite;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.model.ExecutionStep;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.ExecutionStepRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

@Service
public class ExecutionProgressService {

	/**
	 * How rarely the item percentage reaches the database. Far below what anybody
	 * watching a bar can perceive, and far above what ffmpeg reports.
	 */
	private static final Duration ITEM_PROGRESS_INTERVAL = Duration.ofSeconds(1);

	/**
	 * The last write per execution, in this process. An optimisation, never the
	 * truth - losing it costs one extra write.
	 */
	private final Map<Long, ItemProgressWrite> lastItemWrites = new ConcurrentHashMap<>();

	private final ExecutionRepository executionRepository;
	private final ExecutionItemProgressWriter executionItemProgressWriter;
	private final ExecutionStepRepository executionStepRepository;
	private final ExecutionMessageCodec messageCodec;
	private final Clock clock;

	public ExecutionProgressService(ExecutionRepository executionRepository,
			ExecutionItemProgressWriter executionItemProgressWriter, ExecutionStepRepository executionStepRepository,
			ExecutionMessageCodec messageCodec, Clock clock) {
		this.executionRepository = executionRepository;
		this.executionItemProgressWriter = executionItemProgressWriter;
		this.executionStepRepository = executionStepRepository;
		this.messageCodec = messageCodec;
		this.clock = clock;
	}

	/**
	 * Records that the work moved on to another phase. The status is not touched:
	 * an execution being worked on is RUNNING throughout, and which part of it is
	 * happening is what the phase answers.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void updatePhase(Execution execution, ExecutionPhase phase, ExecutionStepType stepType,
			ExecutionMessage message) {
		Execution managed = findExecution(execution);

		managed.setPhase(phase);

		applyMessage(managed, message);

		saveStep(managed, stepType, null, message);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void updateProgress(Execution execution, int filesFound, int filesAnalyzed, int cacheHits, int errors,
			Path currentFile) {
		Execution managed = findExecution(execution);

		managed.setFilesFound(filesFound);
		managed.setFilesAnalyzed(filesAnalyzed);
		managed.setCacheHits(cacheHits);
		managed.setErrors(errors);

		ExecutionMessage message = currentFile == null ? ExecutionMessages.progressUpdated()
				: ExecutionMessages.processingFile(currentFile);

		applyMessage(managed, message);

		saveStep(managed, ExecutionStepType.PROGRESS_UPDATED, currentFile, message);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void updateProgress(Execution execution, int filesFound, int filesAnalyzed, int cacheHits, int errors,
			String currentItem) {
		Execution managed = findExecution(execution);

		managed.setFilesFound(filesFound);
		managed.setFilesAnalyzed(filesAnalyzed);
		managed.setCacheHits(cacheHits);
		managed.setErrors(errors);

		ExecutionMessage message = currentItem == null ? ExecutionMessages.progressUpdated()
				: ExecutionMessages.processing(currentItem);

		applyMessage(managed, message);

		saveStep(managed, ExecutionStepType.PROGRESS_UPDATED, null, message);
	}

	/**
	 * Lightweight, high-frequency progress refresh used <em>while</em> a chunk is
	 * still being processed, so the live progress screen advances smoothly instead
	 * of freezing between chunk commits. It updates the execution row's counters
	 * and message but records <strong>no</strong> {@link ExecutionStep}: the
	 * per-chunk {@link #updateProgress} keeps the durable step trail, so these
	 * granular updates never flood the steps table.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void updateLiveProgress(Execution execution, int filesFound, int filesAnalyzed, int cacheHits, int errors,
			ExecutionMessage message) {
		Execution managed = findExecution(execution);

		managed.setFilesFound(filesFound);
		managed.setFilesAnalyzed(filesAnalyzed);
		managed.setCacheHits(cacheHits);
		managed.setErrors(errors);

		applyMessage(managed, message);
	}

	/**
	 * How far into the item being worked on right now.
	 *
	 * <p>
	 * The one number a bar counting finished items cannot show, and the reason it
	 * has to be persisted rather than remembered: a single video can occupy an
	 * execution for hours, and the process encoding it is no longer the one drawing
	 * the bar.
	 *
	 * <p>
	 * Written far less often than it is reported. ffmpeg speaks every second or so
	 * and a database write per line would be a write per second per encode, for a
	 * number nobody can read that fast. Two filters, both cheap: the whole percent
	 * has to have changed, and {@link #ITEM_PROGRESS_INTERVAL} has to have passed.
	 * The state that remembers the last write is memory in the process doing the
	 * work, which is right - it is an optimisation, not the truth. The truth is the
	 * column.
	 */
	public void updateCurrentItem(Execution execution, int percent) {
		int bounded = Math.clamp(percent, 0, 100);

		if (!worthWriting(execution.getId(), bounded)) {
			return;
		}

		// Written by a bean of its own so the transaction it declares actually
		// applies: a transactional method called from inside its own class never
		// passes the proxy, and the change would reach a detached entity.
		executionItemProgressWriter.write(execution, bounded);
	}

	/**
	 * A new item is starting, so the second level goes back to zero without asking
	 * the throttle: leaving the previous file's number on screen while the next one
	 * begins is exactly the stale progress the column exists to prevent.
	 */
	public void startsCurrentItem(Execution execution) {
		executionItemProgressWriter.write(execution, 0);

		lastItemWrites.put(execution.getId(), new ItemProgressWrite(0, clock.instant()));
	}

	/**
	 * Nothing is mid-item once the execution has ended, so the column says so and
	 * the throttle forgets it. Without the second half a worker that ran for days
	 * would keep one entry per execution it had ever run.
	 */
	private void endsCurrentItem(Execution managed) {
		managed.setCurrentItemPercent(null);

		lastItemWrites.remove(managed.getId());
	}

	private boolean worthWriting(Long executionId, int percent) {
		if (executionId == null) {
			return false;
		}

		Instant now = clock.instant();

		ItemProgressWrite last = lastItemWrites.get(executionId);

		if (last != null && (last.percent() == percent
				|| Duration.between(last.at(), now).compareTo(ITEM_PROGRESS_INTERVAL) < 0)) {
			return false;
		}

		lastItemWrites.put(executionId, new ItemProgressWrite(percent, now));

		return true;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void updateTotal(Execution execution, int totalExpected) {
		Execution managed = findExecution(execution);

		managed.setTotalExpected(totalExpected);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void finish(Execution execution, ExecutionStatus status, int filesFound, int filesAnalyzed, int cacheHits,
			int errors, ExecutionMessage message) {
		Execution managed = findExecution(execution);

		managed.setStatus(status);
		managed.setFinishedAt(LocalDateTime.now(clock));
		managed.setFilesFound(filesFound);
		managed.setFilesAnalyzed(filesAnalyzed);
		managed.setCacheHits(cacheHits);
		managed.setErrors(errors);

		applyMessage(managed, message);

		endsCurrentItem(managed);

		saveStep(managed, ExecutionStepType.FINISHED, null, message);
	}

	/**
	 * Finishes a reconcile with the two numbers that mean something for it: what
	 * the walk found on disk, and how many catalog entries it corrected.
	 *
	 * <p>
	 * Separate from {@link #finish} because reconcile fills different counters -
	 * it analyses nothing, hits no cache and moves no file - and passing zeros
	 * through the general one is how those counters came to say nothing at all.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void finishReconcile(Execution execution, int filesOnDisk, int repairedItems, ExecutionMessage message) {
		Execution managed = findExecution(execution);

		managed.setStatus(ExecutionStatus.FINISHED);
		managed.setFinishedAt(LocalDateTime.now(clock));
		managed.setFilesFound(filesOnDisk);
		managed.setRepairedItems(repairedItems);

		applyMessage(managed, message);

		endsCurrentItem(managed);

		saveStep(managed, ExecutionStepType.FINISHED, null, message);
	}

	/**
	 * Finishes a command that acted on a set of items and has to say what became
	 * of each of them - moved, left alone, failed.
	 *
	 * <p>
	 * Separate from {@link #finish} for the counter {@code finish} cannot write:
	 * how many items the command actually carried out. A rename that reports
	 * nothing moved, or a deletion of forty files that reports none, is a row
	 * whose numbers say the opposite of what happened.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void finishCommand(Execution execution, ExecutionStatus status, ExecutionCounts counts,
			ExecutionMessage message) {
		Execution managed = findExecution(execution);

		managed.setStatus(status);
		managed.setFinishedAt(LocalDateTime.now(clock));
		managed.setFilesFound(counts.found());
		managed.setFilesAnalyzed(counts.found());
		managed.setFilesMoved(counts.moved());
		managed.setCacheHits(counts.skipped());
		managed.setErrors(counts.failed());

		applyMessage(managed, message);

		endsCurrentItem(managed);

		saveStep(managed, ExecutionStepType.FINISHED, null, message);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void cancel(Execution execution, ExecutionMessage message) {
		Execution managed = findExecution(execution);

		managed.setStatus(ExecutionStatus.CANCELLED);
		managed.setFinishedAt(LocalDateTime.now(clock));

		applyMessage(managed, message);

		endsCurrentItem(managed);

		saveStep(managed, ExecutionStepType.CANCELLED, null, message);
	}

	/**
	 * Ends an execution that stopped without failing.
	 *
	 * <p>
	 * Losing the locks is the case this exists for: nothing about the work went
	 * wrong, and the row must not say it did. What happened is that the ground
	 * this execution stood on went away, and someone reading the history later
	 * needs to see exactly that.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void interrupt(Execution execution, ExecutionMessage message) {
		Execution managed = findExecution(execution);

		managed.setStatus(ExecutionStatus.INTERRUPTED);
		managed.setFinishedAt(LocalDateTime.now(clock));

		applyMessage(managed, message);

		endsCurrentItem(managed);

		saveStep(managed, ExecutionStepType.INTERRUPTED, null, message);
	}

	/**
	 * Ends an execution the system decided not to run.
	 *
	 * <p>
	 * Its own status, and deliberately not the one a person's cancel writes. An
	 * execution superseded by an identical request already waiting was refused by
	 * the product; a cancelled one was stopped by somebody. Reading the history to
	 * ask "did my cancel work?" has to be able to tell those apart, and a shared
	 * status with a different sentence in it would not.
	 *
	 * <p>
	 * Transactional like every sibling, and it was not: the entity comes back
	 * detached, so without a transaction the status and the finish time were
	 * changed on an object nobody would ever write. What did get written was the
	 * step - {@code save} brings its own transaction - so the history showed the
	 * execution being rejected over and over while the row stayed RUNNING, and a
	 * request waiting behind it could never be claimed.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void reject(Execution execution, ExecutionMessage message) {
		Execution managed = findExecution(execution);

		managed.setStatus(ExecutionStatus.REJECTED);
		managed.setFinishedAt(LocalDateTime.now(clock));

		applyMessage(managed, message);

		endsCurrentItem(managed);

		saveStep(managed, ExecutionStepType.REJECTED, null, message);
	}

	/**
	 * Same as {@link #reject}, and it was missing the transaction for the same
	 * reason.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void fail(Execution execution, ExecutionMessage message) {
		Execution managed = findExecution(execution);

		managed.setStatus(ExecutionStatus.ERROR);
		managed.setFinishedAt(LocalDateTime.now(clock));

		applyMessage(managed, message);

		endsCurrentItem(managed);

		saveStep(managed, ExecutionStepType.ERROR, null, message);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordError(Execution execution, Path file, String errorDetail, int filesFound, int filesAnalyzed,
			int cacheHits, int errors) {
		Execution managed = findExecution(execution);

		managed.setFilesFound(filesFound);
		managed.setFilesAnalyzed(filesAnalyzed);
		managed.setCacheHits(cacheHits);
		managed.setErrors(errors);

		String path = file == null ? null : file.toAbsolutePath().normalize().toString();

		if (path == null) {
			// No path to identify the file: keep the raw error text as a legacy message,
			// there is no stable code for an arbitrary failure string.
			managed.setStatusMessage(StatusMessage.raw(errorDetail));
		} else {
			applyMessage(managed, ExecutionMessages.errorProcessingFile(path));
		}

		// The step keeps the raw, dynamic error text verbatim (not a catalog message).
		saveStepRaw(managed, ExecutionStepType.FILE_ERROR, file, errorDetail);
	}

	/**
	 * Stores a stable message code plus its typed args on the execution and clears
	 * the legacy free-text {@code message}, so new rows are never identified by
	 * their text. Shared with call sites that finish the execution themselves (e.g.
	 * the organization executor) so the persisted shape stays identical everywhere.
	 */
	public void applyMessage(Execution execution, ExecutionMessage message) {
		execution.setStatusMessage(StatusMessage.coded(message.code(), messageCodec.encode(message.args())));
	}

	private Execution findExecution(Execution execution) {
		return executionRepository.findById(execution.getId())
				.orElseThrow(() -> new IllegalStateException("Execution not found: " + execution.getId()));
	}

	private void saveStep(Execution execution, ExecutionStepType stepType, Path path, ExecutionMessage message) {
		executionStepRepository.save(ExecutionStep.builder().execution(execution).stepType(stepType)
				.path(path == null ? null : path.toAbsolutePath().normalize().toString())
				.statusMessage(StatusMessage.coded(message.code(), messageCodec.encode(message.args())))
				.filesFound(execution.getFilesFound()).filesAnalyzed(execution.getFilesAnalyzed())
				.cacheHits(execution.getCacheHits()).errors(execution.getErrors()).build());
	}

	private void saveStepRaw(Execution execution, ExecutionStepType stepType, Path path, String rawMessage) {
		executionStepRepository.save(ExecutionStep.builder().execution(execution).stepType(stepType)
				.path(path == null ? null : path.toAbsolutePath().normalize().toString())
				.statusMessage(StatusMessage.raw(rawMessage)).filesFound(execution.getFilesFound())
				.filesAnalyzed(execution.getFilesAnalyzed()).cacheHits(execution.getCacheHits())
				.errors(execution.getErrors()).build());
	}
}