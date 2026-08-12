package br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionPossession;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * Who is still working, asked by somebody who was not there when it started.
 *
 * <p>
 * The application used to answer this from a set of ids in its own memory,
 * which could only ever answer for itself - and, once the work moved to the
 * worker, was permanently empty there. What replaced it is the claim: the same
 * statement that writes RUNNING writes the owner and the lease, so the question
 * has one answer and any process can read it.
 *
 * <p>
 * Deliberately not {@code @Transactional}: two roles inside one transaction
 * would see each other's uncommitted work, which is the opposite of what two
 * processes do. The two "sides" here are two commits, which is exactly the
 * visibility a second process gets.
 */
@SpringBootTest
@Testcontainers
class ExecutionLivenessIntegrationTest {

	private static final int MAX_CLAIMS = 3;
	private static final int LEASE_SECONDS = 120;

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	/**
	 * The clock the application writes with, so what this test compares against is
	 * in the same frame as what production stored. {@code LocalDateTime.now()} reads
	 * the JVM's default zone while the row was written in the configured one, and
	 * on any machine where the two differ - every CI runner - a fresh lease looked
	 * hours expired and an expired one looked fresh.
	 */
	@Autowired
	private Clock clock;

	@Autowired
	private ExecutionQueue executionQueue;

	@Autowired
	private ExecutionRepository executionRepository;

	@BeforeEach
	void emptyTheQueue() {
		executionRepository.deleteAll();
	}

	/**
	 * A claims and holds; B asks and finds nothing to recover. B knows nothing
	 * about A - no shared object, no set, no id it was told about - and still gets
	 * the right answer, which is the whole property.
	 */
	@Test
	void aRunHeldByOneProcessIsSeenAsAliveByAnother() {
		Execution queued = enqueue(ExecutionType.INVENTORY, "D:\\held");

		ClaimedExecution claimed = reserve("worker-a").orElseThrow();

		Assertions.assertThat(claimed.id()).isEqualTo(queued.getId());
		Assertions.assertThat(executionQueue.expiredLeases()).doesNotContain(queued.getId());

		Execution row = executionRepository.findById(queued.getId()).orElseThrow();

		Assertions.assertThat(row.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
		Assertions.assertThat(row.getClaimedBy()).isEqualTo("worker-a");
		Assertions.assertThat(row.getLeaseUntil()).isAfter(LocalDateTime.now(clock));
	}

	/**
	 * The heartbeat is what keeps the answer true. A long run stays alive for as
	 * long as its owner renews, and the renewal is refused to anybody else - which
	 * is what stops a second worker from extending a lease it does not hold.
	 */
	@Test
	void renewingKeepsItAliveAndOnlyItsOwnerMayRenew() {
		Execution queued = enqueue(ExecutionType.INVENTORY, "D:\\renewed");

		reserve("worker-a").orElseThrow();

		LocalDateTime before = executionRepository.findById(queued.getId()).orElseThrow().getLeaseUntil();

		Assertions.assertThat(executionQueue.renewLeases("worker-b", List.of(taking(queued, "worker-b")),
				LEASE_SECONDS)).isEmpty();
		Assertions.assertThat(executionQueue.renewLeases("worker-a", List.of(taking(queued, "worker-a")),
				LEASE_SECONDS)).hasSize(1);

		Assertions.assertThat(executionRepository.findById(queued.getId()).orElseThrow().getLeaseUntil())
				.isAfter(before);
		Assertions.assertThat(executionQueue.expiredLeases()).doesNotContain(queued.getId());
	}

	/**
	 * And a lease that ran out is over for good. The heartbeat cannot bring the
	 * taking back, not even before anybody has recovered the row: recovery takes
	 * {@code lease_until < now} and renewal requires {@code lease_until >= now} -
	 * one boundary, written from both sides. Without this a late heartbeat could
	 * revive a taking that recovery had already given up on, and the row would
	 * have two answers to who owns it.
	 */
	@Test
	void aTakingWhoseLeaseRanOutCannotRenewItselfBackToLife() {
		Execution queued = enqueue(ExecutionType.INVENTORY, "D:\\lapsed");

		reserve("worker-a").orElseThrow();

		expireTheLeaseOf(queued);

		Assertions.assertThat(executionQueue.expiredLeases()).contains(queued.getId());
		Assertions.assertThat(executionQueue.renewLeases("worker-a", List.of(taking(queued, "worker-a")),
				LEASE_SECONDS)).isEmpty();
		Assertions.assertThat(executionQueue.expiredLeases()).contains(queued.getId());
	}

	private ExecutionPossession taking(Execution execution, String workerId) {
		return new ExecutionPossession(execution.getId(), workerId,
				executionRepository.findById(execution.getId()).orElseThrow().getClaimCount());
	}

	/**
	 * The owner stopped renewing - killed, or cut off from the database. Only then
	 * does the other side see abandoned work, and nothing had to tell it so.
	 */
	@Test
	void aRunWhoseOwnerStoppedRenewingBecomesAbandonedToEveryone() {
		Execution queued = enqueue(ExecutionType.INVENTORY, "D:\\abandoned");

		reserve("worker-dead").orElseThrow();

		Assertions.assertThat(executionQueue.expiredLeases()).doesNotContain(queued.getId());

		expireTheLeaseOf(queued);

		Assertions.assertThat(executionQueue.expiredLeases()).contains(queued.getId());
		Assertions.assertThat(executionQueue.requeue(queued.getId())).isTrue();

		Execution waiting = executionRepository.findById(queued.getId()).orElseThrow();

		Assertions.assertThat(waiting.getStatus()).isEqualTo(ExecutionStatus.PENDING);
		Assertions.assertThat(waiting.getClaimedBy()).isNull();
		Assertions.assertThat(waiting.getLeaseUntil()).isNull();
	}

	/**
	 * The case the old set existed to prevent, now prevented by the row: a process
	 * starting up while another one is working must not declare that work dead.
	 * Recovery is driven by the same list either side reads, and a held lease is
	 * simply not on it.
	 */
	@Test
	void aStartingProcessDoesNotDisturbWorkAnotherIsDoing() {
		Execution held = enqueue(ExecutionType.INVENTORY, "D:\\live");
		Execution lapsed = enqueue(ExecutionType.RECONCILE, "D:\\dead");

		reserve("worker-a").orElseThrow();
		reserve("worker-a").orElseThrow();

		expireTheLeaseOf(lapsed);

		// What a process about to recover asks for, whichever role it is.
		Assertions.assertThat(executionQueue.expiredLeases()).containsExactly(lapsed.getId());

		Execution untouched = executionRepository.findById(held.getId()).orElseThrow();

		Assertions.assertThat(untouched.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
		Assertions.assertThat(untouched.getClaimedBy()).isEqualTo("worker-a");
	}

	/**
	 * A request nobody has taken survives any restart. It was never claimed, so it
	 * has no lease to expire and no owner to lose - recovery has nothing to say
	 * about it, and the next worker simply takes it.
	 */
	@Test
	void aRequestNobodyTookIsNotAbandonedWork() {
		Execution queued = enqueue(ExecutionType.INVENTORY, "D:\\waiting");

		Assertions.assertThat(executionQueue.expiredLeases()).doesNotContain(queued.getId());

		Execution row = executionRepository.findById(queued.getId()).orElseThrow();

		Assertions.assertThat(row.getStatus()).isEqualTo(ExecutionStatus.PENDING);
		Assertions.assertThat(row.getLeaseUntil()).isNull();
	}

	/**
	 * Simulates the owner going away: the row keeps its owner and its status, and
	 * only the lease stops being in the future - which is the state a killed
	 * process leaves behind, since nothing gets to run on the way out.
	 */
	private void expireTheLeaseOf(Execution execution) {
		Execution row = executionRepository.findById(execution.getId()).orElseThrow();

		row.setLeaseUntil(LocalDateTime.now(clock).minusMinutes(1));

		executionRepository.saveAndFlush(row);
	}

	private Optional<ClaimedExecution> reserve(String workerId) {
		return executionQueue.reserve(workerId, List.of(ExecutionType.INVENTORY.name(), ExecutionType.RECONCILE.name()),
				MAX_CLAIMS, LEASE_SECONDS);
	}

	private Execution enqueue(ExecutionType type, String sourcePath) {
		return executionRepository.saveAndFlush(Execution.builder().executionType(type).status(ExecutionStatus.PENDING)
				.sourcePath(sourcePath).claimCount(0).build());
	}
}