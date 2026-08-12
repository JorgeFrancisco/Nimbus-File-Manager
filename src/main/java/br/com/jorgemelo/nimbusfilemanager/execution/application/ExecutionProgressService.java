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
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueue;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * Everything a worker writes about the execution it is running.
 *
 * <p>
 * Every operation here takes the {@link ExecutionOwnership} rather than the
 * {@link Execution}, and that is the authorisation as much as it is the
 * address. The taking names which row and which attempt of it the caller is
 * acting under, so a run that lost its turn and came back cannot write about a
 * row that now belongs to a later taking - and it cannot write about a row it
 * never took either, because there is no way to name one.
 *
 * <p>
 * The recovery frontiers at the bottom are the exception, and deliberately so:
 * they write about a taking that is over, which is why they take the row and
 * answer to a condition of their own.
 */
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

	private final ExecutionQueue executionQueue;
	private final ExecutionRepository executionRepository;
	private final ExecutionItemProgressWriter executionItemProgressWriter;
	private final ExecutionStepRepository executionStepRepository;
	private final ExecutionMessageCodec messageCodec;
	private final ExecutionRateWindow rateWindow;
	private final Clock clock;

	public ExecutionProgressService(ExecutionQueue executionQueue, ExecutionRepository executionRepository,
			ExecutionItemProgressWriter executionItemProgressWriter,
			ExecutionStepRepository executionStepRepository, ExecutionMessageCodec messageCodec,
			ExecutionRateWindow rateWindow, Clock clock) {
		this.executionQueue = executionQueue;
		this.executionRepository = executionRepository;
		this.executionItemProgressWriter = executionItemProgressWriter;
		this.executionStepRepository = executionStepRepository;
		this.messageCodec = messageCodec;
		this.rateWindow = rateWindow;
		this.clock = clock;
	}

	/**
	 * Records that the work moved on to another phase. The status is not touched:
	 * an execution being worked on is RUNNING throughout, and which part of it is
	 * happening is what the phase answers.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void updatePhase(ExecutionOwnership ownership, ExecutionPhase phase, ExecutionStepType stepType,
			ExecutionMessage message) {
		if (!ownership.takingIsStillCurrent()) {
			return;
		}

		Execution managed = findExecution(ownership);

		managed.setPhase(phase);

		applyMessage(managed, message);

		saveStep(managed, stepType, null, message);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void updateProgress(ExecutionOwnership ownership, int filesFound, int filesAnalyzed, int cacheHits,
			int errors, Path currentFile) {
		if (!ownership.takingIsStillCurrent()) {
			return;
		}

		Execution managed = findExecution(ownership);

		applyCounters(managed, filesFound, filesAnalyzed, cacheHits, errors);

		ExecutionMessage message = currentFile == null ? ExecutionMessages.progressUpdated()
				: ExecutionMessages.processingFile(currentFile);

		applyMessage(managed, message);

		saveStep(managed, ExecutionStepType.PROGRESS_UPDATED, currentFile, message);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void updateProgress(ExecutionOwnership ownership, int filesFound, int filesAnalyzed, int cacheHits,
			int errors, String currentItem) {
		if (!ownership.takingIsStillCurrent()) {
			return;
		}

		Execution managed = findExecution(ownership);

		applyCounters(managed, filesFound, filesAnalyzed, cacheHits, errors);

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
	public void updateLiveProgress(ExecutionOwnership ownership, int filesFound, int filesAnalyzed, int cacheHits,
			int errors, ExecutionMessage message) {
		if (!ownership.takingIsStillCurrent()) {
			return;
		}

		Execution managed = findExecution(ownership);

		applyCounters(managed, filesFound, filesAnalyzed, cacheHits, errors);

		applyMessage(managed, message);
	}

	/**
	 * The counters, and the window the estimate is measured over, written together.
	 *
	 * <p>
	 * Together because they are one fact: the window records the count the row now
	 * carries, so a mark that travelled without its counters - or counters that
	 * travelled without their mark - would describe a moment that never existed.
	 * Every progress path goes through here for that reason; the terminal writes
	 * deliberately do not, because a finished run has nothing left to estimate.
	 */
	private void applyCounters(Execution managed, int filesFound, int filesAnalyzed, int cacheHits, int errors) {
		managed.setFilesFound(filesFound);
		managed.setFilesAnalyzed(filesAnalyzed);
		managed.setCacheHits(cacheHits);
		managed.setErrors(errors);

		rateWindow.advance(managed);
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
	public void updateCurrentItem(ExecutionOwnership ownership, int percent) {
		int bounded = Math.clamp(percent, 0, 100);

		if (!ownership.takingIsStillCurrent() || !worthWriting(ownership.executionId(), bounded)) {
			return;
		}

		// Written by a bean of its own so the transaction it declares actually
		// applies: a transactional method called from inside its own class never
		// passes the proxy, and the change would reach a detached entity.
		executionItemProgressWriter.write(ownership.executionId(), bounded);
	}

	/**
	 * A new item is starting, so the second level goes back to zero without asking
	 * the throttle: leaving the previous file's number on screen while the next one
	 * begins is exactly the stale progress the column exists to prevent.
	 */
	public void startsCurrentItem(ExecutionOwnership ownership) {
		if (!ownership.takingIsStillCurrent()) {
			return;
		}

		executionItemProgressWriter.write(ownership.executionId(), 0);

		lastItemWrites.put(ownership.executionId(), new ItemProgressWrite(0, clock.instant()));
	}

	/**
	 * What is happening now has nothing to measure, so the second bar goes away
	 * rather than sitting at a number.
	 *
	 * <p>
	 * The counterpart of {@link #startsCurrentItem(ExecutionOwnership)}, and the
	 * distinction is the whole point: zero means "this item has barely begun",
	 * absent means "there is no item to measure". Writing zero for a stage with no
	 * denominator - publishing files, invalidating a cache - would draw an empty
	 * bar that reads as stuck, which is the same lie as inventing a percentage,
	 * told from the other end.
	 */
	public void clearsCurrentItem(ExecutionOwnership ownership) {
		if (!ownership.takingIsStillCurrent()) {
			return;
		}

		executionItemProgressWriter.clear(ownership.executionId());

		lastItemWrites.remove(ownership.executionId());
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

	private boolean worthWriting(long executionId, int percent) {
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
	public void updateTotal(ExecutionOwnership ownership, int totalExpected) {
		if (!ownership.takingIsStillCurrent()) {
			return;
		}

		findExecution(ownership).setTotalExpected(totalExpected);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void finish(ExecutionOwnership ownership, ExecutionStatus status, int filesFound, int filesAnalyzed,
			int cacheHits, int errors, ExecutionMessage message) {
		if (!ownership.takingIsStillCurrent()) {
			return;
		}

		Execution managed = findExecution(ownership);

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
	public void finishReconcile(ExecutionOwnership ownership, int filesOnDisk, int repairedItems,
			ExecutionMessage message) {
		if (!ownership.takingIsStillCurrent()) {
			return;
		}

		Execution managed = findExecution(ownership);

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
	public void finishCommand(ExecutionOwnership ownership, ExecutionStatus status, ExecutionCounts counts,
			ExecutionMessage message) {
		if (!ownership.takingIsStillCurrent()) {
			return;
		}

		Execution managed = findExecution(ownership);

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

	/**
	 * Finishes a run over a selection somebody made, which may have stopped before
	 * its last item.
	 *
	 * <p>
	 * Separate from {@link #finishCommand} for the one number that one cannot
	 * express: how much of the selection the run actually reached. A restore of
	 * forty files that stopped after four has to say four were seen out of forty,
	 * and a finisher that writes the selection into both columns would report that
	 * it saw them all.
	 *
	 * @param reached what became of the items the run got to, its {@code found}
	 * being how many of {@code selected} that was
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void finishSelection(ExecutionOwnership ownership, ExecutionStatus status, int selected,
			ExecutionCounts reached, ExecutionMessage message) {
		if (!ownership.takingIsStillCurrent()) {
			return;
		}

		Execution managed = findExecution(ownership);

		managed.setStatus(status);
		managed.setFinishedAt(LocalDateTime.now(clock));
		managed.setFilesFound(selected);
		managed.setFilesAnalyzed(reached.found());
		managed.setFilesMoved(reached.moved());
		managed.setCacheHits(reached.skipped());
		managed.setErrors(reached.failed());

		applyMessage(managed, message);

		endsCurrentItem(managed);

		saveStep(managed, ExecutionStepType.FINISHED, null, message);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void cancel(ExecutionOwnership ownership, ExecutionMessage message) {
		if (!ownership.takingIsStillCurrent()) {
			return;
		}

		Execution managed = findExecution(ownership);

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
	public void interrupt(ExecutionOwnership ownership, ExecutionMessage message) {
		if (!ownership.takingIsStillCurrent()) {
			return;
		}

		Execution managed = findExecution(ownership);

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
	public void reject(ExecutionOwnership ownership, ExecutionMessage message) {
		if (!ownership.takingIsStillCurrent()) {
			return;
		}

		Execution managed = findExecution(ownership);

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
	public void fail(ExecutionOwnership ownership, ExecutionMessage message) {
		if (!ownership.takingIsStillCurrent()) {
			return;
		}

		Execution managed = findExecution(ownership);

		managed.setStatus(ExecutionStatus.ERROR);
		managed.setFinishedAt(LocalDateTime.now(clock));

		applyMessage(managed, message);

		endsCurrentItem(managed);

		saveStep(managed, ExecutionStepType.ERROR, null, message);
	}

	/**
	 * Ends an execution nobody is renewing any more, because it has no attempts
	 * left to spend.
	 *
	 * <p>
	 * One of the three recovery frontiers. They are deliberately not the methods
	 * the work itself uses: a worker writes about the taking it is running, and
	 * recovery writes about a taking that is over - different authorities, and the
	 * predicate that authorises each is different too. Recovery's is the condition
	 * inside the statement below; the worker's is the ownership it carries.
	 *
	 * @return whether this pass is the one that ended it, and so the one that may
	 * act on having done so
	 */
	@Transactional
	public boolean failAbandoned(Execution execution, ExecutionMessage message) {
		return endAbandoned(execution, ExecutionStatus.ERROR, ExecutionStepType.ERROR, message);
	}

	/** The abandoned execution that cannot simply be run again. */
	@Transactional
	public boolean interruptAbandoned(Execution execution, ExecutionMessage message) {
		return endAbandoned(execution, ExecutionStatus.INTERRUPTED, ExecutionStepType.INTERRUPTED, message);
	}

	/** The abandoned execution an identical request is already waiting to replace. */
	@Transactional
	public boolean rejectSuperseded(Execution execution, ExecutionMessage message) {
		return endAbandoned(execution, ExecutionStatus.REJECTED, ExecutionStepType.REJECTED, message);
	}

	/**
	 * The whole of a recovery transition: one conditional statement for the row,
	 * and the history for it, in one short transaction.
	 *
	 * <p>
	 * Nothing here touches the entity. The statement is the only write to
	 * {@code execution}, so there is no second pass over the row that could put
	 * back a state somebody else had already changed - which is what a
	 * read-modify-write after the winning update would be, however short the gap.
	 * The step joins the same transaction rather than bringing its own, so a row
	 * that ends cannot be left without the history that explains it, nor a history
	 * written for a transition that did not happen.
	 */
	private boolean endAbandoned(Execution execution, ExecutionStatus status, ExecutionStepType stepType,
			ExecutionMessage message) {
		String encodedArgs = messageCodec.encode(message.args());

		if (!executionQueue.endAbandoned(execution.getId(), status, message.code(), encodedArgs)) {
			return false;
		}

		saveStep(execution, stepType, null, message);

		// Local only, and only for the pass that won: it throttles the per-item
		// progress writes and has no part in what was just committed.
		lastItemWrites.remove(execution.getId());

		return true;
	}

	/**
	 * Stores a stable message code plus its typed args on the execution and clears
	 * the legacy free-text {@code message}, so new rows are never identified by
	 * their text.
	 *
	 * <p>
	 * Private, and that is the point of it: it mutates a row without asking whose
	 * row it is, so it is a step inside an operation that has already asked - never
	 * a door a caller can reach for. Every writer outside this class arrives
	 * through an operation above, which names the taking it is writing under.
	 */
	private void applyMessage(Execution execution, ExecutionMessage message) {
		// No sentence means leave the one the row already has. A caller with nothing
		// new to say is ordinary - a counter moving under the same step - and it used
		// to take the whole update down with a null dereference, which cost a
		// geographic dataset update every time a file came back unchanged.
		if (message == null) {
			return;
		}

		execution.setStatusMessage(StatusMessage.coded(message.code(), messageCodec.encode(message.args())));
	}

	private Execution findExecution(ExecutionOwnership ownership) {
		return executionRepository.findById(ownership.executionId())
				.orElseThrow(() -> new IllegalStateException("Execution not found: " + ownership.executionId()));
	}

	/**
	 * The step carries no sentence when the caller had none, for the same reason
	 * the row above keeps the one it had: a counter moving under the same step is
	 * ordinary. Dereferencing the absent message here was the null the guard in
	 * {@code applyMessage} was added for, one method later - it took the whole
	 * progress transaction down, and the step with it.
	 */
	private void saveStep(Execution execution, ExecutionStepType stepType, Path path, ExecutionMessage message) {
		executionStepRepository.save(ExecutionStep.builder().execution(execution).stepType(stepType)
				.path(path == null ? null : path.toAbsolutePath().normalize().toString())
				.statusMessage(message == null ? null
						: StatusMessage.coded(message.code(), messageCodec.encode(message.args())))
				.filesFound(execution.getFilesFound()).filesAnalyzed(execution.getFilesAnalyzed())
				.cacheHits(execution.getCacheHits()).errors(execution.getErrors()).build());
	}
}