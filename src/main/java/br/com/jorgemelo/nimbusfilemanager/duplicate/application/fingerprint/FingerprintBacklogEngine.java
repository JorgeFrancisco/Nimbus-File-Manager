package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.BatchCounts;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DrainResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintFailureReason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.FingerprintFailure;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintFailureRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintRebuildTaskRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.FingerprintFailureDetail;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionStatusNames;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ProcessingCoordinator;
import br.com.jorgemelo.nimbusfilemanager.processing.application.dto.Outcome;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionMetricsContext;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ProcessingMetrics;

/**
 * Media-agnostic engine that drains a fingerprint backlog OUTSIDE the
 * inventory. It knows nothing about photos or videos: it drives a
 * {@link FingerprintProducer} through the batch loop (fetch pending → hash in
 * parallel on the shared {@link ProcessingCoordinator} → persist the batch in
 * its own short transaction), records/retires failures, and yields to any
 * active inventory. Because "done"/"failed" are the {@code media_fingerprint}/
 * {@code fingerprint_failure} rows themselves, a crash only loses the in-flight
 * batch and the next run re-derives the rest.
 *
 * <p>
 * This is the common core reused by every fingerprint kind: the photo and video
 * backlog services each hold one and pass themselves as the producer, so the
 * drain, transaction handling, retry bounds and rebuild/reset live in exactly
 * one place - with no {@code PHOTO}/{@code VIDEO} branching.
 */
@Component
class FingerprintBacklogEngine {


	static final int BATCH_SIZE = 200;
	private static final int MAX_ERROR_LENGTH = 500;
	/**
	 * How many items are written at a time, and - for a producer that groups them -
	 * how many go into one invocation of its tool. Small on purpose: it is the
	 * amount of finished work a stop can still throw away, and the two are the same
	 * number so that a group and a write are one unit rather than two that have to
	 * be kept in step.
	 */
	private static final int PERSIST_SIZE = 25;

	private final MediaFingerprintRepository mediaFingerprintRepository;
	private final FingerprintFailureRepository fingerprintFailureRepository;
	private final FingerprintRebuildTaskRepository fingerprintRebuildTaskRepository;
	private final ProcessingCoordinator processingCoordinator;
	private final ExecutionRepository executionRepository;
	private final TransactionTemplate writeTransaction;
	private final Clock clock;

	public FingerprintBacklogEngine(MediaFingerprintRepository mediaFingerprintRepository,
			FingerprintFailureRepository fingerprintFailureRepository,
			FingerprintRebuildTaskRepository fingerprintRebuildTaskRepository,
			ProcessingCoordinator processingCoordinator, ExecutionRepository executionRepository,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.mediaFingerprintRepository = mediaFingerprintRepository;
		this.fingerprintFailureRepository = fingerprintFailureRepository;
		this.fingerprintRebuildTaskRepository = fingerprintRebuildTaskRepository;
		this.processingCoordinator = processingCoordinator;
		this.executionRepository = executionRepository;
		this.writeTransaction = new TransactionTemplate(transactionManager);
		this.writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.clock = clock;
	}

	/**
	 * True while an inventory execution is active. Kept apart from
	 * {@link #pausedByActiveExecution()} because the Duplicados screen shows the
	 * inventory's own progress with it, and a conversion is not an inventory.
	 */
	public boolean inventoryActive() {
		return activeTypeIsOneOf(ExecutionType.INVENTORY);
	}

	/**
	 * True while a conversion is running. It holds the quarantine folder to move
	 * originals into it, which is exactly where a duplicate deletion has to write,
	 * so the Duplicados screen has to say so before the user picks files instead of
	 * refusing the click afterwards.
	 */
	public boolean conversionActive() {
		return activeTypeIsOneOf(ExecutionType.CONVERSION);
	}

	/**
	 * True while an execution the backlog has to step aside for is running.
	 *
	 * <p>
	 * An inventory, because it is about to hand the backlog more work and racing it
	 * only wastes both. A conversion, because it competes for the very same scarce
	 * resource - ffmpeg processes and the hardware encoder - on work the user is
	 * sitting in front of, waiting. Fingerprints have nobody waiting on them:
	 * stopping costs nothing, since the next run reads what is still pending from
	 * the database and everything already computed was persisted per batch.
	 */
	public boolean pausedByActiveExecution() {
		return activeTypeIsOneOf(ExecutionType.INVENTORY, ExecutionType.CONVERSION);
	}

	/**
	 * Asked of every active row rather than of the most recent one: a worker runs
	 * several executions at a time, so "the newest active execution is an
	 * inventory" answers a different question - and answers this one wrong
	 * whenever something else started later.
	 */
	private boolean activeTypeIsOneOf(ExecutionType... executionTypes) {
		return Arrays.stream(executionTypes)
				.anyMatch(type -> executionRepository.existsByExecutionTypeAndStatusIn(type,
						ExecutionStatusNames.ACTIVE));
	}

	/**
	 * What is left, and the two ways of asking it.
	 *
	 * <p>
	 * While a rebuild is open, what is owed is what the work list says - the
	 * fingerprints it is going to replace are still there, so the ordinary
	 * question ("which files have none?") would answer zero and call a rebuild
	 * that has not started finished. Outside a rebuild the ordinary question is
	 * the right one, and the two are never added together: exactly one of them is
	 * asked.
	 */
	public FingerprintBacklogStatus status(FingerprintProducer<?, ?> producer) {
		long done = mediaFingerprintRepository.countFingerprintedCatalogFiles(producer.kind(), producer.algorithm());

		long failed = producer.countExhaustedFailures();

		long owed = fingerprintRebuildTaskRepository.countByKindAndAlgorithm(producer.kind(), producer.algorithm());

		return new FingerprintBacklogStatus(owed > 0 ? owed : producer.countPending(), done, failed);
	}

	public List<FingerprintFailureDetail> failures(FingerprintProducer<?, ?> producer) {
		return producer.exhaustedFailures();
	}

	/** Manual retry: exhausted (retryable) failures return to the pending queue. */
	public long resetFailures(FingerprintProducer<?, ?> producer) {
		return fingerprintFailureRepository.deleteRetryableByKindAndAlgorithm(producer.kind(), producer.algorithm(),
				FingerprintFailureReason.retryable());
	}

	/**
	 * Writes down what a rebuild owes, instead of deleting what it is going to
	 * replace.
	 *
	 * <p>
	 * The fingerprints stay exactly where they are. Every eligible file of the
	 * kind gets a task, and each one is replaced later by its own short
	 * transaction, so the answer the library gives never passes through empty:
	 * before this, re-opening the work meant deleting the data, and a run
	 * interrupted after that left the whole algorithm missing until something
	 * recomputed it.
	 *
	 * <p>
	 * The attempt budget is restored rather than the failures being erased. What
	 * made a file fail is the diagnosis the screen shows - the reason, the error,
	 * when it was last tried - and none of it stops being true because the user
	 * asked for a recompute; what has to change is only that the file is allowed
	 * to be tried again.
	 *
	 * <p>
	 * Seeding and the reset are one transaction because a rebuild that owed work
	 * nobody was allowed to attempt would never finish, and a budget restored for
	 * work nobody owes would let the next incremental pass retry files this run
	 * never claimed.
	 *
	 * @return empty when the taking is over, in which case nothing was seeded and
	 * no budget was restored
	 */
	public OptionalLong seedRebuild(FingerprintProducer<?, ?> producer, ExecutionOwnership ownership) {
		return Objects.requireNonNull(writeTransaction.execute(_ -> {
			if (!ownership.pin()) {
				return OptionalLong.empty();
			}

			long seeded = producer.seedRebuildTasks(LocalDateTime.now(clock));

			fingerprintFailureRepository.restoreAttemptBudget(producer.kind(), producer.algorithm());

			return OptionalLong.of(seeded);
		}));
	}

	/** Whether a rebuild of this target still owes anything. */
	public boolean rebuildIsOpen(FingerprintProducer<?, ?> producer) {
		return fingerprintRebuildTaskRepository.existsByKindAndAlgorithm(producer.kind(), producer.algorithm());
	}

	/**
	 * Drops the debts of files that stopped being candidates while the rebuild ran.
	 *
	 * <p>
	 * Its own short transaction, and deliberately not part of persisting a chunk: a
	 * file that went missing produced no outcome to write down, and treating it as
	 * one would mean inventing a result for work that never ran. What it is instead
	 * is a debt that can no longer be paid, and the only thing standing between
	 * that and a rebuild which never closes is dropping it.
	 *
	 * <p>
	 * Pinned like every other mutation the worker makes: a run that has been
	 * replaced does not get to decide what the taking after it still owes.
	 */
	public int discardIneligible(FingerprintProducer<?, ?> producer, ExecutionOwnership ownership) {
		return Objects.requireNonNull(
				writeTransaction.execute(_ -> ownership.pin() ? producer.discardIneligibleRebuildTasks() : 0));
	}

	/**
	 * Drains the pending queue until empty, cancelled, or higher-priority work
	 * takes over. The heavy hashing runs off-transaction on the coordinator; only
	 * the persistence is transactional.
	 *
	 * <p>
	 * Rows are fetched in large batches, because that query is cheap, and written
	 * in small ones, because that write is the only thing standing between minutes
	 * of ffmpeg and losing it: whatever a run computed but had not yet stored dies
	 * with the process. A restart used to throw away up to a whole batch - one run
	 * hashed for thirty-nine minutes and left nothing behind. A write is
	 * {@code PERSIST_SIZE} items whatever the producer, and what a kill can still
	 * throw away is whatever was computed before the write it belonged to - one
	 * write for a producer working item by item, and the fetched batch for one that
	 * groups, which is seconds of photo hashing rather than minutes of video
	 * sampling, and is the price of letting the tool gate decide how many groups
	 * run at once instead of this loop rationing them.
	 */
	public <P, R> DrainResult drain(FingerprintProducer<P, R> producer, BooleanSupplier stop,
			ProgressListener progress, ExecutionOwnership ownership, ExecutionMetricsContext metricsContext) {

		long processed = 0;

		long failed = 0;

		// Checked per item, not only between batches: a batch is BATCH_SIZE videos and
		// each one costs seconds of ffmpeg, so waiting for the batch boundary would
		// keep competing with the conversion for several minutes after it started.
		// A taking that has been replaced stops here too - that answer is free, comes
		// from memory, and only saves work: what refuses the write is the pin below.
		BooleanSupplier halt = () -> stop.getAsBoolean() || pausedByActiveExecution()
				|| !ownership.takingIsStillCurrent();

		// Before reading anything, settle the debts that cannot be paid. A file the
		// catalog lost sight of since the seed never reaches a chunk, so nothing else
		// in this loop would ever account for it, and the rebuild would stay open on
		// it for as long as it stayed away.
		if (rebuildIsOpen(producer)) {
			discardIneligible(producer, ownership);
		}

		while (!halt.getAsBoolean()) {
			List<P> batch = producer.fetchPendingBatch(BATCH_SIZE);

			if (batch.isEmpty()) {
				break;
			}

			int chunkSize = chunkSize(producer, batch);

			for (int start = 0; start < batch.size() && !halt.getAsBoolean(); start += chunkSize) {
				List<P> chunk = batch.subList(start, Math.min(start + chunkSize, batch.size()));

				long baseProcessed = processed;
				long baseFailed = failed;

				List<Outcome<P, R>> outcomes = computeChunk(producer, chunk, halt, metricsContext.processing(),
						done -> progress.onProgress(baseProcessed + done, baseFailed));

				for (int first = 0; first < outcomes.size(); first += PERSIST_SIZE) {
					List<Outcome<P, R>> written = outcomes.subList(first,
							Math.min(first + PERSIST_SIZE, outcomes.size()));

					// A write is one unit against the taking: everything it writes - the
					// fingerprint, the failure it retires on success, the failure it records -
					// is inside this transaction, behind this pin. Work computed by a run that
					// has since been replaced is thrown away whole rather than half written,
					// which is the only shape that cannot contradict the taking that replaced
					// it.
					BatchCounts counts = writeTransaction
							.execute(_ -> ownership.pin() ? persistBatch(producer, written) : null);

					if (counts == null) {
						return new DrainResult(processed, failed);
					}

					processed += counts.done();

					failed += counts.failed();

					progress.onProgress(processed, failed);
				}
			}
		}

		return new DrainResult(processed, failed);
	}

	/**
	 * How many items are computed before anything is written.
	 *
	 * <p>
	 * A producer that groups is handed everything that was fetched, because the
	 * groups are what the pool schedules and how many of them run at once is the
	 * tool gate's decision - a configured limit, not this loop's. Handing out one
	 * group at a time would override that limit with a worse one, leaving every
	 * worker but one waiting on a process it could have been running beside.
	 */
	private <P, R> int chunkSize(FingerprintProducer<P, R> producer, List<P> batch) {
		return producer instanceof GroupedFingerprintProducer ? batch.size() : PERSIST_SIZE;
	}

	private <P, R> List<Outcome<P, R>> computeChunk(FingerprintProducer<P, R> producer, List<P> chunk,
			BooleanSupplier halt, ProcessingMetrics metrics, IntConsumer onCompleted) {

		if (producer instanceof GroupedFingerprintProducer<P, R> grouped) {
			return computeInGroups(grouped, chunk, halt, metrics, onCompleted);
		}

		return processingCoordinator.process(chunk, halt, pending -> producer.compute(pending, metrics), metrics,
				onCompleted);
	}

	/**
	 * The pool's unit of work becomes the group, not the item.
	 *
	 * <p>
	 * Which is also what the task counters in {@link ProcessingMetrics} then
	 * describe, and correctly so - they are about what the pool scheduled and what
	 * the tool was made to run, and for a grouped producer that is one invocation
	 * per group. How many files were done is the execution's own counter, and it
	 * goes on counting files: the progress reported below is in items, and so is
	 * everything persisted from here on.
	 */
	private <P, R> List<Outcome<P, R>> computeInGroups(GroupedFingerprintProducer<P, R> producer, List<P> chunk,
			BooleanSupplier halt, ProcessingMetrics metrics, IntConsumer onCompleted) {

		List<List<P>> groups = partition(chunk, PERSIST_SIZE);

		List<Outcome<List<P>, List<Outcome<P, R>>>> computed = processingCoordinator.process(groups, halt,
				group -> producer.computeGroup(group, metrics), metrics,
				done -> onCompleted.accept(Math.min(done * PERSIST_SIZE, chunk.size())));

		return expand(computed);
	}

	private static <P> List<List<P>> partition(List<P> items, int size) {
		List<List<P>> groups = new ArrayList<>();

		for (int start = 0; start < items.size(); start += size) {
			groups.add(items.subList(start, Math.min(start + size, items.size())));
		}

		return groups;
	}

	/**
	 * Turns each group's single outcome back into one per item.
	 *
	 * <p>
	 * A group that failed or was cancelled says the same thing about every item in
	 * it, because it never got far enough to tell them apart. A group that ran says
	 * one thing per item, and each of those already names the item it is about -
	 * nothing here pairs an answer with an item by position, which is why an
	 * invocation that came back the wrong length has to be caught where the pairing
	 * is actually made, inside the producer. An item the producer chose not to
	 * answer for is simply left out, which leaves it pending for the next run.
	 */
	private static <P, R> List<Outcome<P, R>> expand(List<Outcome<List<P>, List<Outcome<P, R>>>> computed) {
		List<Outcome<P, R>> outcomes = new ArrayList<>();

		for (Outcome<List<P>, List<Outcome<P, R>>> group : computed) {
			if (group.executed()) {
				outcomes.addAll(group.value());
			} else if (group.failed()) {
				group.item().forEach(item -> outcomes.add(Outcome.error(item, group.error())));
			} else {
				group.item().forEach(item -> outcomes.add(Outcome.cancelled(item)));
			}
		}

		return outcomes;
	}

	private <P, R> BatchCounts persistBatch(FingerprintProducer<P, R> producer, List<Outcome<P, R>> outcomes) {
		long done = 0;

		long failed = 0;

		for (Outcome<P, R> outcome : outcomes) {
			P item = outcome.item();

			if (outcome.executed()) {
				replace(producer, item, outcome.value());

				done++;
			} else if (outcome.failed()) {
				recordFailure(producer, item, outcome.error());

				failed++;
			}
			// CANCELLED: left pending, picked up by the next run.
		}

		return new BatchCounts(done, failed);
	}

	/**
	 * One file changing hands, whole.
	 *
	 * <p>
	 * The old rows go before the new ones arrive, because a replacement may be a
	 * different size: a video re-read at a new duration is sampled at different
	 * frames, and writing over it sample by sample would leave the tail of the old
	 * set behind, attributed to a hash that never produced it.
	 *
	 * <p>
	 * What was concluded from the old fingerprint goes with it. Nothing published
	 * can tell a relation computed from the hash that was just replaced from one
	 * computed from the hash that replaced it - the composition digest a grouping
	 * carries is over the files it examined, not over their fingerprints - so a
	 * relation left behind would be indistinguishable from a current one and would
	 * be read as an answer.
	 *
	 * <p>
	 * All of it inside the caller's pinned transaction, so a file is either
	 * entirely replaced or entirely untouched.
	 */
	private <P, R> void replace(FingerprintProducer<P, R> producer, P item, R value) {
		long catalogFileId = producer.catalogFileId(item);

		mediaFingerprintRepository.deleteByCatalogFileIdAndKindAndAlgorithm(catalogFileId, producer.kind(),
				producer.algorithm());

		producer.store(item, value);

		// A prior failed attempt that later succeeds must not linger as a failure.
		fingerprintFailureRepository.deleteByCatalogFileIdAndKindAndAlgorithm(catalogFileId, producer.kind(),
				producer.algorithm());

		producer.forgetWhatWasDerivedFrom(catalogFileId);

		fingerprintRebuildTaskRepository.consume(producer.kind(), producer.algorithm(), catalogFileId);
	}

	private <P, R> void recordFailure(FingerprintProducer<P, R> producer, P item, Exception error) {
		long catalogFileId = producer.catalogFileId(item);

		FingerprintFailure failure = fingerprintFailureRepository
				.findByCatalogFileIdAndKindAndAlgorithm(catalogFileId, producer.kind(), producer.algorithm())
				.orElseGet(() -> FingerprintFailure.builder().catalogFileId(catalogFileId).kind(producer.kind())
						.algorithm(producer.algorithm()).attempts(0).build());

		FingerprintFailureReason reason = producer.reason(item, error);

		// A terminal reason spends every attempt at once: the file will not decode on
		// the next pass either, and letting attempts climb only means decoding it again
		// on every run.
		failure.setAttempts(reason.terminal() ? producer.maxAttempts() : failure.getAttempts() + 1);
		failure.setReason(reason);
		failure.setLastError(truncate(error));
		failure.setLastAttemptAt(LocalDateTime.now(clock));

		fingerprintFailureRepository.save(failure);

		// A rebuild owes a file until something is written down about it, and a spent
		// budget is an answer: this one will not decode, and no later pass is going to
		// change that. Owing it anyway would keep the rebuild open on a file nobody
		// will ever attempt again. An attempt that is still allowed leaves the debt
		// where it is, in the same transaction that recorded the failure.
		if (failure.getAttempts() >= producer.maxAttempts()) {
			fingerprintRebuildTaskRepository.consume(producer.kind(), producer.algorithm(), catalogFileId);
		}
	}

	private String truncate(Exception error) {
		String message = error == null ? "unknown error" : String.valueOf(error.getMessage());

		return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
	}
}