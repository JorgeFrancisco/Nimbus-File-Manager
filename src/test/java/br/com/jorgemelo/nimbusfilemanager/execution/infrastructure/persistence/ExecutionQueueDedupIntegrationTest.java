package br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * What the claim may take when an identical request is already running.
 *
 * <p>
 * The queue allows one waiting and one running of the same request, and those
 * are two partial unique indexes rather than one so that the pair is legal.
 * What follows from that, and did not use to be stated anywhere, is that the
 * waiting one cannot be <em>claimed</em> while the running one runs: claiming
 * writes RUNNING for a key that already has a RUNNING row, and the index
 * refuses it.
 *
 * <p>
 * Nothing in one worker ever reached that, because its own per-type limit
 * happened to hide it - the running row occupies the only slot, so the type is
 * left out of the question entirely. It stops hiding it the moment the running
 * row belongs to a worker that is gone: a fresh worker's limits are empty, the
 * pair is exactly what a crash leaves behind, and the claim then fails with an
 * integrity violation on every round for as long as the abandoned row stays
 * RUNNING.
 *
 * <p>
 * Only PostgreSQL can show any of this, which is why it is here and not in a
 * unit test: the rule being broken is an index, and the query being fixed is
 * SQL.
 */
@SpringBootTest
@Transactional
@Testcontainers
class ExecutionQueueDedupIntegrationTest {

	private static final String WORKER = "worker-under-test";
	private static final String KEY = "d:\\fotos";
	private static final int MAX_CLAIMS = 3;
	private static final int LEASE_SECONDS = 60;

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

	@Autowired
	private ExecutionQueue executionQueue;

	@Autowired
	private ExecutionRepository executionRepository;

	/**
	 * The case that used to raise. Without the exclusion in the claim this is an
	 * integrity violation rather than an empty answer - and an empty answer is
	 * what "there is nothing I may take" has to look like.
	 */
	@Test
	void leavesAWaitingRequestAloneWhileAnIdenticalOneIsStillRunning() {
		abandonedRunning(KEY);
		waiting(KEY);

		assertThat(reserve()).isEmpty();
	}

	@Test
	void claimsTheWaitingRequestOnceTheRunningOneIsOver() {
		Execution running = abandonedRunning(KEY);
		Execution waiting = waiting(KEY);

		finish(running);

		assertThat(reserve()).get().extracting(ClaimedExecution::id).isEqualTo(waiting.getId());
	}

	/**
	 * The exclusion is about one request, not about the type: two inventories of
	 * two different folders are two different jobs, and one of them running has
	 * nothing to say about the other.
	 */
	@Test
	void claimsAWaitingRequestForAKeyNothingIsRunning() {
		abandonedRunning(KEY);

		Execution otherFolder = waiting("d:\\videos");

		assertThat(reserve()).get().extracting(ClaimedExecution::id).isEqualTo(otherFolder.getId());
	}

	/**
	 * A null key opts out of deduplication entirely - that is how conversions and
	 * undos share these indexes with the types that are deduplicated - so it must
	 * opt out of this too, or two of them would queue behind each other forever.
	 */
	@Test
	void claimsAWaitingRequestThatCarriesNoKeyAtAll() {
		abandonedRunning(null);

		Execution unkeyed = waiting(null);

		assertThat(reserve()).get().extracting(ClaimedExecution::id).isEqualTo(unkeyed.getId());
	}

	private Optional<ClaimedExecution> reserve() {
		return executionQueue.reserve(WORKER, List.of(ExecutionType.INVENTORY.name()), MAX_CLAIMS, LEASE_SECONDS);
	}

	/**
	 * A row a dead worker left behind: RUNNING, claimed, and with a lease that
	 * lapsed. Recovery would deal with it at the next start; what this class is
	 * about is the interval before that.
	 */
	private Execution abandonedRunning(String dedupKey) {
		Execution running = request(dedupKey, ExecutionStatus.RUNNING);

		running.setClaimedBy("worker-that-is-gone");
		running.setClaimedAt(LocalDateTime.now().minusHours(1));
		running.setLeaseUntil(LocalDateTime.now().minusMinutes(30));

		return executionRepository.saveAndFlush(running);
	}

	private Execution waiting(String dedupKey) {
		return executionRepository.saveAndFlush(request(dedupKey, ExecutionStatus.PENDING));
	}

	private Execution request(String dedupKey, ExecutionStatus status) {
		return Execution.builder().executionType(ExecutionType.INVENTORY).status(status).sourcePath("D:\\fotos")
				.dedupKey(dedupKey).recursive(true).executeFlag(true).build();
	}

	private void finish(Execution execution) {
		execution.setStatus(ExecutionStatus.FINISHED);
		execution.setFinishedAt(LocalDateTime.now());

		executionRepository.saveAndFlush(execution);
	}
}