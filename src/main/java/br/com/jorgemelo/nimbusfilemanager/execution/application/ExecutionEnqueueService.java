package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionStatusNames;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueueNotifier;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Asking for work to be done.
 *
 * <p>
 * The row is written PENDING and that is the whole of it - whoever asked does
 * not run anything, and gets an execution id to poll straight away. What used
 * to be a background thread started here is now a worker claiming from the
 * queue, which is what lets the work outlive this process and happen in
 * another.
 *
 * <p>
 * Duplicate requests are refused by the database rather than by a look-then-act
 * check: two clicks arriving together would both find nothing and both insert.
 * A violation of the partial unique index means an identical request is already
 * waiting or running, and the honest answer to "please scan this folder again"
 * is the scan that is already coming.
 *
 * <p>
 * The row and the wake-up share one transaction, in that order, and the
 * transaction is explicit here rather than declarative because the duplicate
 * has to be caught outside it: a violation leaves the transaction unable to
 * commit, so catching it inside an {@code @Transactional} method would swap one
 * exception for another instead of answering "already queued".
 *
 * <p>
 * <strong>A refusal is logged by the persistence layer before it can get
 * here.</strong> Hibernate reports every {@code SQLException} at error level on
 * its way out, so a duplicate that was refused exactly as designed still leaves
 * an error in the log. Nothing in this class can precede that: the writing
 * happens below the level any {@code catch} reaches.
 *
 * <p>
 * So the answer is to ask before inserting, rather than to insert and be
 * refused. That removes the case which is not a race at all - the request that
 * was already on the queue, committed long before anybody asked again, which is
 * what every boot looks like once recovery has put the previous one back. What
 * it cannot remove is the genuine race, where two callers both find nothing and
 * both insert; there the index refuses one of them, and the error it writes
 * stays. That is known noise, and the alternative is worse than the noise.
 *
 * <p>
 * The alternative would be {@code INSERT ... ON CONFLICT DO NOTHING}, which has
 * to be written by hand - and the insert it would replace is generated from the
 * entity: thirty-odd columns, the defaults its {@code @PrePersist} fills in, an
 * embedded status message, a JSON column and a generated key. Hand-writing it
 * would put a second description of how an execution is stored beside the first,
 * whose way of going wrong is a column added to the entity and forgotten in the
 * SQL - data silently not saved, on the one primitive every request in the
 * product goes through. Suppressing the logger instead would hide integrity
 * violations that are real. Neither trade is worth a log line.
 */
@Slf4j
@Service
public class ExecutionEnqueueService {

	/**
	 * What "already waiting" means, and it is deliberately not "already active".
	 *
	 * <p>
	 * The queue allows one waiting and one running of the same request - that is
	 * the whole reason there are two partial indexes rather than one. Asking about
	 * both would answer a second request with the execution that is <em>running</em>
	 * and never queue its successor, which is the 1 + 1 rule silently becoming
	 * 1 + 0.
	 */
	private static final Set<ExecutionStatus> WAITING = Set.of(ExecutionStatus.PENDING);

	/**
	 * The indexes whose violation is a deduplication answer rather than a fault,
	 * named in {@code V16__execution_becomes_the_work_queue.sql}. Any other
	 * integrity violation is somebody else's problem and is not to be reported as
	 * "already queued".
	 */
	private static final Set<String> DEDUPLICATION_INDEXES = Set.of("ux_execution_pending_dedup",
			"ux_execution_running_dedup");

	/** A cause chain can be a ring, so the walk is bounded rather than trusted. */
	private static final int MAX_CAUSE_DEPTH = 32;

	private final ExecutionRepository executionRepository;
	private final ExecutionQueueNotifier executionQueueNotifier;
	private final TransactionTemplate writeTransaction;
	private final Clock clock;

	public ExecutionEnqueueService(ExecutionRepository executionRepository,
			ExecutionQueueNotifier executionQueueNotifier, PlatformTransactionManager transactionManager,
			Clock clock) {
		this.executionRepository = executionRepository;
		this.executionQueueNotifier = executionQueueNotifier;
		this.writeTransaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/**
	 * @return the queued execution, or the empty optional when an identical
	 * request is already pending or running
	 */
	public Optional<Execution> enqueue(Execution request) {
		request.setStatus(ExecutionStatus.PENDING);
		request.setCreatedAt(LocalDateTime.now(clock));
		request.setAvailableAt(request.getCreatedAt());
		request.setStartedAt(null);

		try {
			return Optional.of(writeTransaction.execute(_ -> {
				Execution queued = executionRepository.saveAndFlush(request);

				executionQueueNotifier.workWasQueued();

				return queued;
			}));
		} catch (DataIntegrityViolationException violation) {
			if (!refusedByDeduplication(violation)) {
				// Not our refusal. Answering "already queued" to a violation of some other
				// constraint would report the wrong thing and hand back the wrong row.
				throw violation;
			}

			// Said at info rather than debug because it is the explanation for an error
			// somebody will otherwise read as a fault: the integrity violation the
			// persistence layer reports for this insert is this refusal, and a refusal is
			// how deduplication works here.
			log.info("A {} for {} is already queued, and the request was answered with it", request.getExecutionType(),
					request.getDedupKey());

			return Optional.empty();
		}
	}

	/**
	 * The same request, answered with the one already waiting when the database
	 * refuses it as a duplicate.
	 *
	 * <p>
	 * For whoever has somebody looking at a screen: a second click on the same
	 * thing is not an error and not a second scan - it is the first one, and
	 * saying so is what makes clicking twice harmless. Only for types that carry a
	 * deduplication key; without one there is nothing to look up and nothing was
	 * refused.
	 *
	 * @throws IllegalStateException when the duplicate that caused the refusal
	 * cannot be found, which would mean the index refused a request for a reason
	 * nobody has described
	 */
	public Execution enqueueOrExisting(Execution request) {
		return alreadyWaiting(request).orElseGet(() -> enqueue(request).orElseGet(() -> reusedOrAskedAgain(request)));
	}

	/**
	 * The request already on the queue, asked before inserting rather than after
	 * being refused.
	 *
	 * <p>
	 * The index is still the authority - two callers arriving together both find
	 * nothing here, both insert, and one of them is refused, which is the case the
	 * path below exists for. What this removes is the other case, the one that is
	 * not a race at all: at startup the application asks for both fingerprint
	 * backlogs unconditionally, and recovery has usually just put the previous
	 * request back on the queue. That row was committed long before anybody asked,
	 * so letting the database refuse it produced an error in the log for a
	 * perfectly ordinary boot - and an error nobody can catch early enough to
	 * explain, because the persistence layer writes it before it throws.
	 *
	 * <p>
	 * It asks only about what is <em>waiting</em>. A running one does not forbid a
	 * successor, and answering with it would quietly stop the successor from ever
	 * being queued.
	 */
	private Optional<Execution> alreadyWaiting(Execution request) {
		if (request.getDedupKey() == null) {
			return Optional.empty();
		}

		return executionRepository.findFirstByExecutionTypeAndDedupKeyAndStatusInOrderByCreatedAtDesc(
				request.getExecutionType(), request.getDedupKey(), WAITING);
	}

	/**
	 * Whether this violation is the deduplication index doing its job.
	 *
	 * <p>
	 * The constraint name is the only thing that tells the two apart, and the
	 * persistence layer carries it: a duplicate public id, a broken foreign key or
	 * a null in a column that forbids one are all integrity violations too, and
	 * none of them means "already queued".
	 */
	private boolean refusedByDeduplication(DataIntegrityViolationException violation) {
		Throwable cause = violation;

		for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
			if (cause instanceof ConstraintViolationException constraint) {
				return constraint.getConstraintName() != null
						&& DEDUPLICATION_INDEXES.contains(constraint.getConstraintName().toLowerCase(Locale.ROOT));
			}

			cause = cause.getCause();
		}

		return false;
	}

	/**
	 * The one already queued - or, when it is no longer queued, this request
	 * again.
	 *
	 * <p>
	 * The second case is a real interval, not a defensive gesture: the refusal and
	 * the lookup that follows it are two statements, and between them the row that
	 * caused the refusal can finish. Answering that with an exception would fail a
	 * request for the very reason it should have succeeded - the thing in its way
	 * is gone - and the person would see an error for having clicked at an
	 * unlucky moment.
	 *
	 * <p>
	 * Asked once more, never in a loop. A second refusal means something is really
	 * there, and it is looked up rather than guessed at.
	 *
	 * @throws IllegalStateException when nothing was queued either time, which
	 * would mean the index refused a request for a reason nobody has described
	 */
	private Execution reusedOrAskedAgain(Execution request) {
		return alreadyQueued(request).or(() -> enqueue(request)).or(() -> alreadyQueued(request))
				.orElseThrow(() -> new IllegalStateException("A " + request.getExecutionType()
						+ " was refused as a duplicate but none is queued for " + request.getDedupKey()));
	}

	private Optional<Execution> alreadyQueued(Execution request) {
		return executionRepository.findFirstByExecutionTypeAndDedupKeyAndStatusInOrderByCreatedAtDesc(
				request.getExecutionType(), request.getDedupKey(), ExecutionStatusNames.ACTIVE);
	}
}