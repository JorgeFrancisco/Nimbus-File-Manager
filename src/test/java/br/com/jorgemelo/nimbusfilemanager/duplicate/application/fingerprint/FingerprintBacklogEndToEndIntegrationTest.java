package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Clock;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogPayload;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.worker.application.ExecutionReclaim;

/**
 * A fingerprint backlog going all the way through the queue, against a real
 * database and a real worker.
 *
 * <p>
 * It exists because of what the acceptance run showed: both fingerprint types
 * had unit tests, migrated history and a green build, and neither had ever once
 * crossed the dispatcher. Every row of theirs in that catalog was either
 * written by the engine that used to run inside the application or claimed and
 * then thrown out of before {@code claim_count} could move. Nothing was
 * asserting the one path that mattered - asked for, claimed, run, finished -
 * because everything asserting it was a mock.
 *
 * <p>
 * The request is made through the launcher rather than assembled here, so what
 * is claimed is the payload the product really writes: a handler that refuses
 * the schema it is given would end as an error rather than as a pass.
 */
@SpringBootTest
@ActiveProfiles(NimbusProfiles.APP_WORKER_COMBINED)
@Testcontainers
class FingerprintBacklogEndToEndIntegrationTest {

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
	private FingerprintBacklogLauncher fingerprintBacklogLauncher;

	@Autowired
	private ExecutionPayloadCodec executionPayloadCodec;

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private ExecutionReclaim executionReclaim;

	/**
	 * Both media, and the attempt counted exactly once. A rebuild is asked for
	 * because a plain drain over a catalog with nothing pending is deliberately not
	 * queued at all - which is correct, and is also why nothing here ever ran.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "FINGERPRINT_PHOTO", "FINGERPRINT_VIDEO" })
	void isAskedForClaimedRunAndFinished(String type) {
		Execution queued = fingerprintBacklogLauncher.launch(ExecutionType.valueOf(type), true).orElseThrow();

		Execution finished = awaitTerminal(queued.getId());

		assertThat(finished.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
		assertThat(finished.getClaimedBy()).isNotNull();
		assertThat(finished.getClaimCount()).isEqualTo(1);
	}

	/**
	 * The loop that ran for a month, written down so it cannot come back. A worker
	 * dies holding a backlog, the lease lapses, recovery puts the row back - and
	 * the run that follows has to reach a terminal status with an attempt on the
	 * record. A row that came back and ended again at zero is the shape that was
	 * reclaimed and re-thrown on every restart, forever, because the poison-job
	 * brake reads exactly the counter that never moved.
	 */
	@Test
	void isRunToTheEndAfterAWorkerDiedHoldingIt() {
		Execution abandoned = executionRepository.saveAndFlush(leftBehindByADeadWorker());

		executionReclaim.reclaimAbandoned();

		Execution finished = awaitTerminal(abandoned.getId());

		assertThat(finished.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
		assertThat(finished.getClaimCount()).isPositive();
	}

	private Execution leftBehindByADeadWorker() {
		return Execution.builder().executionType(ExecutionType.FINGERPRINT_PHOTO).status(ExecutionStatus.RUNNING)
				.claimedBy("worker-that-is-gone").claimedAt(LocalDateTime.now(clock).minusHours(1))
				.leaseUntil(LocalDateTime.now(clock).minusMinutes(30)).claimCount(0).recursive(false).executeFlag(true)
				.dedupKey("FINGERPRINT_PHOTO:recovered")
				.requestPayload(executionPayloadCodec.encode(
						new FingerprintBacklogPayload(DuplicateConstants.FINGERPRINT_PAYLOAD_SCHEMA_VERSION, false)))
				.build();
	}

	private Execution awaitTerminal(Long executionId) {
		return await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).until(
				() -> executionRepository.findById(executionId).orElseThrow(),
				execution -> execution.getStatus().isTerminal());
	}
}