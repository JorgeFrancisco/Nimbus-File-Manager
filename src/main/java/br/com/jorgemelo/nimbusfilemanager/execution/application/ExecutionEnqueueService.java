package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
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
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.QueueAdmissionLockRepository;
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
 * <b>Admission is what makes this safe inside somebody else's transaction.</b>
 * The template below propagates as {@code REQUIRED}, so a caller already in a
 * transaction is joined rather than wrapped - and a refusal there marks
 * <em>that</em> transaction rollback-only. The caller learns of it at its own
 * commit, far from any {@code catch}: an {@code UnexpectedRollbackException}
 * that broke the Files screen on a second listing, and an inventory batch that
 * lost every write it had made, in silence. Both were measured against a real
 * PostgreSQL before they were fixed.
 *
 * <p>
 * So a refusal is no longer part of the ordinary path. Every admission takes a
 * transaction advisory lock on the identity first, then looks, then writes. A
 * second caller waits rather than races, and by the time it looks the first has
 * committed and there is something to find - which is the honest answer to
 * "please scan this folder again": the scan that is already coming.
 *
 * <p>
 * <b>Three policies, one mechanism.</b> All that differs between them is which
 * statuses already represent the intention. {@link #enqueue} and
 * {@link #enqueueAll} yield to one that is waiting; {@link #enqueueOrExisting}
 * answers with it; and {@link #enqueueUnlessAlreadyActive} counts a running one
 * as representing it too. The locking, the ordering and the writing are
 * identical for all of them and live here - never in a caller, which is what
 * keeps the protocol in one place.
 *
 * <p>
 * <b>Only a caller that repeats itself may treat a running execution as its
 * answer</b>, and that is one caller: the timer. Everyone else is reacting to
 * something observed - a walk that found a divergence, a screen showing an entry
 * the disk no longer has - and a running execution has already looked at what it
 * looked at. It cannot take in a fact that arrived afterwards, and the caller
 * cannot hand one to work in progress. The waiting successor is what carries
 * that fact, which is the whole of the 1 + 1 rule: refusing it drops an
 * observation nothing makes again on its own, and no later pass is owed for it.
 *
 * <p>
 * <b>The unique indexes remain the authority.</b> Nothing here defines what
 * makes two requests equivalent; the partial indexes do, and the catch below
 * stays for any path that somehow reaches them without having taken the lock.
 * What changed is that reaching them is no longer how deduplication works.
 *
 * <p>
 * That is also why the insert is still the entity's own. {@code INSERT ... ON
 * CONFLICT DO NOTHING} would have to be written by hand, and the insert it
 * replaces is generated from a mapping with thirty-odd columns, the defaults its
 * {@code @PrePersist} fills in, an embedded status message, a JSON column and a
 * generated key. A second description of how an execution is stored fails by a
 * column added to the entity and forgotten in the SQL - data silently not saved,
 * on the one primitive every request in the product goes through.
 *
 * <p>
 * <strong>A refusal, when one does happen, is logged by the persistence layer
 * before it can get here.</strong> Hibernate reports every {@code SQLException}
 * at error level on its way out, so nothing in this class can precede it: the
 * writing happens below the level any {@code catch} reaches. That is now a rare
 * event rather than the routine one it used to be.
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
	 * What already represents a standing intention, for the callers that have one.
	 *
	 * <p>
	 * Both statuses, which is exactly what {@link #WAITING} may not be. The 1 + 1
	 * rule exists so a person's second request is never dropped on the floor; a
	 * timer has no second request to lose - it has the same one, again - so for it
	 * anything active already says what the tick was going to say.
	 */
	private static final Set<ExecutionStatus> ACTIVE = Set.of(ExecutionStatus.PENDING, ExecutionStatus.RUNNING);

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
	private final QueueAdmissionLockRepository queueAdmissionLockRepository;
	private final TransactionTemplate writeTransaction;
	private final Clock clock;

	public ExecutionEnqueueService(ExecutionRepository executionRepository,
			ExecutionQueueNotifier executionQueueNotifier,
			QueueAdmissionLockRepository queueAdmissionLockRepository,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.executionRepository = executionRepository;
		this.executionQueueNotifier = executionQueueNotifier;
		this.queueAdmissionLockRepository = queueAdmissionLockRepository;
		this.writeTransaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/**
	 * @return the queued execution, or the empty optional when an identical
	 * request is already waiting
	 */
	public Optional<Execution> enqueue(Execution request) {
		return admitAll(List.of(request), WAITING).getFirst();
	}

	/**
	 * Every request of one transaction, admitted together, under the same rule
	 * {@link #enqueue} applies: a waiting request refuses a second one, a running
	 * one does not.
	 *
	 * <p>
	 * <b>One call per transaction, and that is a rule rather than a convenience.</b>
	 * Admission takes an advisory lock per identity and holds it until the caller
	 * commits, so a transaction admitting several requests holds several locks. Two
	 * transactions taking the same pair in opposite orders would deadlock - the
	 * 23505 traded for a 40P01 - and the only thing that rules that out is a total
	 * order over the keys, which can only be imposed by whoever sees the whole set.
	 * Splitting one transaction's admissions across two calls gives two ordered
	 * sets with no order between them, and the cycle is back.
	 *
	 * @return one answer per request, in the order given: the queued execution, or
	 * empty where an equivalent one was already waiting
	 */
	public List<Optional<Execution>> enqueueAll(List<Execution> requests) {
		return admitAll(requests, WAITING);
	}

	/**
	 * Takes the locks for the whole set, in ascending key order, and only then
	 * looks and writes. Nothing here decides what equivalence means - the statuses
	 * passed in say which admission this is, and the unique indexes remain the last
	 * word.
	 */
	private List<Optional<Execution>> admitAll(List<Execution> requests, Set<ExecutionStatus> blocking) {
		queueAdmissionLockRepository.take(admissionKeysOf(requests));

		return requests.stream().map(request -> admitOne(request, blocking)).toList();
	}

	/**
	 * The identities this set needs held, deduplicated and sorted by the very
	 * number the lock is taken on. A request without a deduplication key has no
	 * identity to serialise on and takes no lock: nothing can be equivalent to it,
	 * so nothing can race it.
	 */
	private Long[] admissionKeysOf(List<Execution> requests) {
		return requests.stream().filter(request -> request.getDedupKey() != null)
				.map(request -> QueueAdmissionKey.of(request.getExecutionType(), request.getDedupKey())).distinct()
				.sorted().toArray(Long[]::new);
	}

	private Optional<Execution> admitOne(Execution request, Set<ExecutionStatus> blocking) {
		if (equivalent(request, blocking).isPresent()) {
			log.debug("A {} for {} is already represented; the request it would repeat was not queued again",
					request.getExecutionType(), request.getDedupKey());

			return Optional.empty();
		}

		return insert(request);
	}

	/**
	 * The insert itself, with the refusal still caught.
	 *
	 * <p>
	 * Unreachable in the ordinary case now, and kept anyway: the indexes are the
	 * authority, and a path that somehow reaches one without having taken the lock
	 * must still be answered rather than thrown.
	 */
	private Optional<Execution> insert(Execution request) {
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
	 * The same request, for a caller that will make it again on its own - and
	 * therefore has nothing to gain from a successor.
	 *
	 * <p>
	 * A timer asks the same question on every tick. When the work takes longer than
	 * the interval - a reconcile of a hundred and forty thousand files against a
	 * five minute tick - the 1 + 1 rule leaves one running and one always waiting,
	 * and the queue never drains: every pass is immediately followed by the
	 * successor the previous tick left, while the tick after that leaves another.
	 * Observed as five reconciles in fifteen minutes over one library, two of them
	 * ending in error having examined nothing.
	 *
	 * <p>
	 * So a periodic request is admitted only while nothing equivalent is active.
	 * Nothing is lost by refusing it: the intention is already represented, and the
	 * next tick comes anyway.
	 *
	 * <p>
	 * <b>This is admission, not a second deduplication.</b> The unique indexes stay
	 * the authority - two ticks that somehow read "nothing active" together still
	 * meet the same refusal every other caller meets, and one of them is answered
	 * with the row the other wrote. What this adds is the question asked before the
	 * insert, so the ordinary case is a decision rather than an integrity violation
	 * in the log.
	 *
	 * @return the queued execution, or empty when one was already active - which is
	 * an answer rather than a failure
	 */
	public Optional<Execution> enqueueUnlessAlreadyActive(Execution request) {
		return admitAll(List.of(request), ACTIVE).getFirst();
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
		return equivalent(request, WAITING);
	}

	/**
	 * Whether something equivalent is already there, over whichever statuses the
	 * admission in hand treats as blocking. One predicate for all three policies,
	 * because the question never changes - only the answer's scope does. Without a
	 * key there is nothing to be equivalent to, so nothing is refused.
	 */
	private Optional<Execution> equivalent(Execution request, Set<ExecutionStatus> blocking) {
		if (request.getDedupKey() == null) {
			return Optional.empty();
		}

		return executionRepository.findFirstByExecutionTypeAndDedupKeyAndStatusInOrderByCreatedAtDesc(
				request.getExecutionType(), request.getDedupKey(), blocking);
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