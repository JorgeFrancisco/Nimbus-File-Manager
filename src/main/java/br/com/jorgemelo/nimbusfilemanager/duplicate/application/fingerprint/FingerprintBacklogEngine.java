package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.BatchCounts;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DrainResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.FingerprintFailure;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintFailureRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.FingerprintFailureDetail;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ProcessingCoordinator;
import br.com.jorgemelo.nimbusfilemanager.processing.application.dto.Outcome;

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
class FingerprintBacklogEngine {

	private static final String INVENTORY = "INVENTORY";
	private static final String CONVERSION = "CONVERSION";

	static final int BATCH_SIZE = 200;
	static final String UNSUPPORTED_PREFIX = "[unsupported] ";
	private static final int MAX_ERROR_LENGTH = 500;
	private static final int PROGRESS_STRIDE = 25;

	private final MediaFingerprintRepository mediaFingerprintRepository;
	private final FingerprintFailureRepository fingerprintFailureRepository;
	private final ProcessingCoordinator processingCoordinator;
	private final ExecutionQueryService executionQueryService;
	private final TransactionTemplate writeTransaction;
	private final Clock clock;

	public FingerprintBacklogEngine(MediaFingerprintRepository mediaFingerprintRepository,
			FingerprintFailureRepository fingerprintFailureRepository, ProcessingCoordinator processingCoordinator,
			ExecutionQueryService executionQueryService, PlatformTransactionManager transactionManager, Clock clock) {
		this.mediaFingerprintRepository = mediaFingerprintRepository;
		this.fingerprintFailureRepository = fingerprintFailureRepository;
		this.processingCoordinator = processingCoordinator;
		this.executionQueryService = executionQueryService;
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
		return activeTypeIsOneOf(INVENTORY);
	}

	/**
	 * True while an execution the backlog has to step aside for is running.
	 *
	 * <p>
	 * An inventory, because it is about to hand the backlog more work and racing
	 * it only wastes both. A conversion, because it competes for the very same
	 * scarce resource - ffmpeg processes and the hardware encoder - on work the
	 * user is sitting in front of, waiting. Fingerprints have nobody waiting on
	 * them: stopping costs nothing, since the next run reads what is still pending
	 * from the database and everything already computed was persisted per batch.
	 */
	public boolean pausedByActiveExecution() {
		return activeTypeIsOneOf(INVENTORY, CONVERSION);
	}

	private boolean activeTypeIsOneOf(String... executionTypes) {
		return executionQueryService.active()
				.map(execution -> Set.of(executionTypes).contains(execution.executionType())).orElse(false);
	}

	public FingerprintBacklogStatus status(FingerprintProducer<?, ?> producer) {
		long done = mediaFingerprintRepository.countFingerprintedCatalogFiles(producer.kind(), producer.algorithm());

		long failed = producer.countExhaustedFailures();

		long pending = producer.countPending();

		return new FingerprintBacklogStatus(pending, done, failed);
	}

	public List<FingerprintFailureDetail> failures(FingerprintProducer<?, ?> producer) {
		return producer.exhaustedFailures();
	}

	/** Manual retry: exhausted (retryable) failures return to the pending queue. */
	public long resetFailures(FingerprintProducer<?, ?> producer) {
		return fingerprintFailureRepository.deleteRetryableByKindAndAlgorithm(producer.kind(), producer.algorithm(),
				UNSUPPORTED_PREFIX);
	}

	/** Clears only this kind/algorithm's derived fingerprints and failures. */
	public long rebuild(FingerprintProducer<?, ?> producer) {
		Long removed = writeTransaction.execute(_ -> {
			fingerprintFailureRepository.deleteByKindAndAlgorithm(producer.kind(), producer.algorithm());

			return mediaFingerprintRepository.deleteByKindAndAlgorithm(producer.kind(), producer.algorithm());
		});

		return removed == null ? 0 : removed;
	}

	/**
	 * Drains the pending queue until empty, cancelled, or an inventory takes
	 * priority. The heavy hashing runs off-transaction on the coordinator; only the
	 * per-batch persistence is transactional.
	 */
	public <P, R> DrainResult drain(FingerprintProducer<P, R> producer, BooleanSupplier stop,
			ProgressListener progress) {
		long processed = 0;

		long failed = 0;

		// Checked per item, not only between batches: a batch is BATCH_SIZE videos and
		// each one costs seconds of ffmpeg, so waiting for the batch boundary would
		// keep competing with the conversion for several minutes after it started.
		BooleanSupplier halt = () -> stop.getAsBoolean() || pausedByActiveExecution();

		while (!halt.getAsBoolean()) {
			List<P> batch = producer.fetchPendingBatch(BATCH_SIZE);

			if (batch.isEmpty()) {
				break;
			}

			long baseProcessed = processed;
			long baseFailed = failed;

			List<Outcome<P, R>> outcomes = processingCoordinator.process(batch, halt, producer::compute, done -> {
				if (done % PROGRESS_STRIDE == 0) {
					progress.onProgress(baseProcessed + done, baseFailed);
				}
			});

			BatchCounts counts = Objects
					.requireNonNull(writeTransaction.execute(_ -> persistBatch(producer, outcomes)));

			processed += counts.done();

			failed += counts.failed();

			progress.onProgress(processed, failed);
		}

		return new DrainResult(processed, failed);
	}

	private <P, R> BatchCounts persistBatch(FingerprintProducer<P, R> producer, List<Outcome<P, R>> outcomes) {
		long done = 0;

		long failed = 0;

		for (Outcome<P, R> outcome : outcomes) {
			P item = outcome.item();

			if (outcome.executed()) {
				producer.store(item, outcome.value());

				// A prior failed attempt that later succeeds must not linger as a failure.
				fingerprintFailureRepository.deleteByCatalogFileIdAndKindAndAlgorithm(producer.catalogFileId(item),
						producer.kind(), producer.algorithm());

				done++;
			} else if (outcome.failed()) {
				recordFailure(producer, item, outcome.error());

				failed++;
			}
			// CANCELLED: left pending, picked up by the next run.
		}

		return new BatchCounts(done, failed);
	}

	private <P, R> void recordFailure(FingerprintProducer<P, R> producer, P item, Exception error) {
		long catalogFileId = producer.catalogFileId(item);

		FingerprintFailure failure = fingerprintFailureRepository
				.findByCatalogFileIdAndKindAndAlgorithm(catalogFileId, producer.kind(), producer.algorithm())
				.orElseGet(() -> FingerprintFailure.builder().catalogFileId(catalogFileId).kind(producer.kind())
						.algorithm(producer.algorithm()).attempts(0).build());

		boolean unsupported = producer.unsupported(error);

		failure.setAttempts(unsupported ? producer.maxAttempts() : failure.getAttempts() + 1);
		failure.setLastError((unsupported ? UNSUPPORTED_PREFIX : "") + truncate(error));
		failure.setLastAttemptAt(LocalDateTime.now(clock));

		fingerprintFailureRepository.save(failure);
	}

	private String truncate(Exception error) {
		String message = error == null ? "unknown error" : String.valueOf(error.getMessage());

		return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
	}
}