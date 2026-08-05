package br.com.jorgemelo.nimbusfilemanager.shared.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.SelfWrittenPathRepository;

/**
 * One instance announces a write; another, sharing nothing with it but the
 * database, recognises it.
 *
 * <p>
 * That sentence is why the register stopped being a map. The worker moves a file
 * and the application's watcher sees it arrive: memory in either one answers
 * only for itself, and a watcher that cannot tell its own product's write from a
 * stranger's answers a single moved file with a full inventory of the library.
 *
 * <p>
 * Two separate {@link SelfWrittenPathRegistry} instances, each built by hand,
 * with no object between them - no singleton, no cache, no map - so nothing here
 * can pass by accident through shared memory. That is what this proves, and it
 * is worth being exact about what it does not: these are two objects in one JVM,
 * not two processes.
 *
 * <p>
 * The step from one to the other is an argument rather than an experiment, and
 * it rests on there being no shared memory to lose: the register holds no static
 * state, every answer is a query, and the only thing either side reads is a row.
 * A second JVM would repeat these assertions with an operating system in between
 * and exercise no further path through the code. Where a process boundary does
 * add a property of its own - a pid, an exit, a handle - this project starts a
 * real process, as the worker lifecycle tests do.
 */
@SpringBootTest
@Testcontainers
class SelfWrittenPathCrossInstanceIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	@Autowired
	private SelfWrittenPathRepository selfWrittenPathRepository;

	@Autowired
	private ExecutionRepository executionRepository;

	/** The writing side, standing in for the worker. */
	private SelfWrittenPathRegistry writer;

	/** The watching side, standing in for the application. */
	private SelfWrittenPathRegistry watcher;

	/**
	 * Built here rather than as fields: a field initialiser runs before the
	 * repository is injected, and each side has to be an object of its own.
	 */
	@BeforeEach
	void buildBothSides() {
		writer = registry();
		watcher = registry();
	}

	@Test
	void oneSideRecognisesWhatTheOtherAnnounced(@TempDir Path folder) {
		Path written = folder.resolve("moved-by-the-worker.mp4");

		writer.announce(written);

		assertThat(watcher.announcedAmong(List.of(written))).containsExactly(written);
	}

	/**
	 * The other half of the promise, and the one that must not be traded away for
	 * the first: a change nobody announced is a change somebody made, and the
	 * watcher has to see it.
	 */
	@Test
	void doesNotHideAChangeNobodyAnnounced(@TempDir Path folder) {
		Path theirs = folder.resolve("edited-by-somebody.mp4");

		writer.announce(folder.resolve("ours.mp4"));

		assertThat(watcher.announcedAmong(List.of(theirs))).isEmpty();
	}

	/** Both ends of a move, which is what a rename produces notifications for. */
	@Test
	void carriesBothEndsOfAMoveAcross(@TempDir Path folder) {
		Path source = folder.resolve("before.mp4");
		Path target = folder.resolve("after.mp4");
		Path theirs = folder.resolve("theirs.mp4");

		writer.announce(source);
		writer.announce(target);

		assertThat(watcher.announcedAmong(List.of(source, target, theirs)))
				.containsExactlyInAnyOrder(source, target);
	}

	/**
	 * A folder, which is what an Explorer rename of a directory and a sweep of an
	 * empty one leave behind. A delete reaches the watcher without being
	 * inspected - a path already gone cannot be - so it has to be recognisable
	 * exactly like a file.
	 */
	@Test
	void carriesADirectoryAcross(@TempDir Path folder) {
		Path removed = folder.resolve("emptied-folder");

		writer.announce(removed);

		assertThat(watcher.announcedAmong(List.of(removed))).containsExactly(removed);
	}

	/**
	 * The repeated notifications of one write. The name, the size and the last
	 * write are reported separately and a long write spreads them over successive
	 * polls, so recognising it once is not enough - taking the record at the first
	 * match is what used to leave the rest looking foreign.
	 */
	@Test
	void keepsRecognisingTheSameWriteOnEveryLaterPoll(@TempDir Path folder) {
		Path written = folder.resolve("long-encode.mp4");

		writer.announce(written);

		assertThat(watcher.announcedAmong(List.of(written))).containsExactly(written);
		assertThat(watcher.announcedAmong(List.of(written))).containsExactly(written);
		assertThat(watcher.announcedAmong(List.of(written))).containsExactly(written);
	}

	/**
	 * A watcher that starts later - the application restarted while the worker
	 * kept going - reads the same rows. Nothing about recognising a write depends
	 * on having been running when it was announced.
	 */
	@Test
	void isReadableByAnInstanceThatDidNotExistWhenItWasAnnounced(@TempDir Path folder) {
		Path written = folder.resolve("announced-before-the-restart.mp4");

		writer.announce(written);

		assertThat(registry().announcedAmong(List.of(written))).containsExactly(written);
	}

	/**
	 * An entry nobody matched must not silence a path forever, and the ceiling is
	 * read from the clock rather than from when the row happened to be written -
	 * so a watcher whose question comes late gets the honest answer, and the same
	 * path is a foreign change again.
	 */
	@Test
	void stopsRecognisingAWriteOnceItsCeilingHasPassed(@TempDir Path folder) {
		Path written = folder.resolve("nobody-claimed-this.mp4");

		writer.announce(written);

		SelfWrittenPathRegistry muchLater = registryAt(Instant.now().plus(Duration.ofHours(1)));

		assertThat(muchLater.announcedAmong(List.of(written))).isEmpty();
	}

	/**
	 * The sweep runs on every announcement, so the table is bounded by what was
	 * announced inside one window rather than by how long the product has been
	 * running. It cannot cause a false negative either: what it removes is exactly
	 * what the question already refuses by age.
	 */
	@Test
	void sweepsWhatHasExpiredWithoutTouchingWhatIsStillLive(@TempDir Path folder) {
		Path live = folder.resolve("still-being-written.mp4");

		writer.announce(live);

		registryAt(Instant.now().plus(Duration.ofHours(1))).announce(folder.resolve("much-later.mp4"));

		assertThat(watcher.announcedAmong(List.of(live))).isEmpty();
	}

	/**
	 * A single move can outlast the ceiling on its own - fifty gigabytes across two
	 * volumes is minutes of copying - and its notifications keep arriving the whole
	 * time. An entry that expired underneath would turn the second half of one
	 * file's own write into a foreign change, and answer it with a full inventory.
	 *
	 * <p>
	 * What holds it open is the possession that already exists. No second clock and
	 * no heartbeat of its own: the execution is RUNNING and its lease is being
	 * renewed, which is the same fact the rest of the product uses to mean "this is
	 * still being worked on".
	 */
	@Test
	void keepsRecognisingAWriteWhoseExecutionStillHoldsItsPaths(@TempDir Path folder) {
		Path written = folder.resolve("very-large-move.mp4");

		writer.announce(written, runningExecutionLeasedFor(Duration.ofHours(3)));

		SelfWrittenPathRegistry pastTheCeiling = registryAt(Instant.now().plus(Duration.ofHours(1)));

		assertThat(pastTheCeiling.announcedAmong(List.of(written))).containsExactly(written);
	}

	/**
	 * Bounded by the lease and not by the status, so a worker that died cannot
	 * leave a path silenced. Nothing renews the lease of a process that is gone,
	 * and the ceiling applies again the moment it lapses.
	 */
	@Test
	void stopsRecognisingItOnceTheExecutionsLeaseHasLapsed(@TempDir Path folder) {
		Path written = folder.resolve("worker-died-mid-move.mp4");

		writer.announce(written, runningExecutionLeasedFor(Duration.ofMinutes(1)));

		SelfWrittenPathRegistry pastTheCeiling = registryAt(Instant.now().plus(Duration.ofHours(1)));

		assertThat(pastTheCeiling.announcedAmong(List.of(written))).isEmpty();
	}

	/**
	 * The sweep has to honour the same rule as the question, or housekeeping would
	 * quietly delete the row that a still-running move depends on.
	 */
	@Test
	void doesNotSweepAnEntryWhoseExecutionStillHoldsItsPaths(@TempDir Path folder) {
		Path written = folder.resolve("still-being-moved.mp4");

		writer.announce(written, runningExecutionLeasedFor(Duration.ofHours(3)));

		SelfWrittenPathRegistry pastTheCeiling = registryAt(Instant.now().plus(Duration.ofHours(1)));

		pastTheCeiling.announce(folder.resolve("some-other-write.mp4"));

		assertThat(pastTheCeiling.announcedAmong(List.of(written))).containsExactly(written);
	}

	/**
	 * Once the execution has ended, the entry falls back to the ceiling on its own
	 * - which is the margin the design asks for after a terminal state, arriving
	 * without anything having to remember to delete it.
	 */
	@Test
	void fallsBackToTheCeilingOnceTheExecutionHasEnded(@TempDir Path folder) {
		Path written = folder.resolve("finished-move.mp4");

		Execution execution = execution(ExecutionStatus.RUNNING, Duration.ofHours(3));

		writer.announce(written, execution.getId());

		execution.setStatus(ExecutionStatus.FINISHED);

		executionRepository.save(execution);

		assertThat(watcher.announcedAmong(List.of(written))).containsExactly(written);
		assertThat(registryAt(Instant.now().plus(Duration.ofHours(1))).announcedAmong(List.of(written))).isEmpty();
	}

	private Long runningExecutionLeasedFor(Duration lease) {
		return execution(ExecutionStatus.RUNNING, lease).getId();
	}

	private Execution execution(ExecutionStatus status, Duration lease) {
		return executionRepository.save(Execution.builder().executionType(ExecutionType.ORGANIZATION).status(status)
				.leaseUntil(LocalDateTime.now(ZoneOffset.UTC).plus(lease)).build());
	}

	private SelfWrittenPathRegistry registry() {
		return new SelfWrittenPathRegistry(selfWrittenPathRepository, Clock.systemUTC());
	}

	private SelfWrittenPathRegistry registryAt(Instant instant) {
		return new SelfWrittenPathRegistry(selfWrittenPathRepository, Clock.fixed(instant, ZoneOffset.UTC));
	}
}