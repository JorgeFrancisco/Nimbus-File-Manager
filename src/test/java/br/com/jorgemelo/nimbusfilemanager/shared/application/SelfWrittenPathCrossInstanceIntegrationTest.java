package br.com.jorgemelo.nimbusfilemanager.shared.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.condition.OS;

import org.junit.jupiter.api.condition.EnabledOnOs;

import java.io.IOException;

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

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SelfWrittenPath;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.SelfWriteRole;
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
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

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
	void oneSideRecognisesWhatTheOtherAnnounced(@TempDir Path folder) throws IOException {
		Path written = folder.resolve("moved-by-the-worker.mp4");

		writer.occupy(null, () -> { }, written);

		assertThat(watcher.announcedAmong(List.of(occupying(written)))).containsExactly(occupying(written));
	}

	/**
	 * The other half of the promise, and the one that must not be traded away for
	 * the first: a change nobody announced is a change somebody made, and the
	 * watcher has to see it.
	 */
	@Test
	void doesNotHideAChangeNobodyAnnounced(@TempDir Path folder) throws IOException {
		Path theirs = folder.resolve("edited-by-somebody.mp4");

		writer.occupy(null, () -> { }, folder.resolve("ours.mp4"));

		assertThat(watcher.announcedAmong(List.of(occupying(theirs)))).isEmpty();
	}

	/**
	 * A folder, which is what an Explorer rename of a directory and a sweep of an
	 * empty one leave behind. A delete reaches the watcher without being
	 * inspected - a path already gone cannot be - so it has to be recognisable
	 * exactly like a file.
	 */
	@Test
	void carriesADirectoryAcross(@TempDir Path folder) throws IOException {
		Path removed = folder.resolve("emptied-folder");

		writer.occupy(null, () -> { }, removed);

		assertThat(watcher.announcedAmong(List.of(occupying(removed)))).containsExactly(occupying(removed));
	}

	/**
	 * The repeated notifications of one write. The name, the size and the last
	 * write are reported separately and a long write spreads them over successive
	 * polls, so recognising it once is not enough - taking the record at the first
	 * match is what used to leave the rest looking foreign.
	 */
	@Test
	void keepsRecognisingTheSameWriteOnEveryLaterPoll(@TempDir Path folder) throws IOException {
		Path written = folder.resolve("long-encode.mp4");

		writer.occupy(null, () -> { }, written);

		assertThat(watcher.announcedAmong(List.of(occupying(written)))).containsExactly(occupying(written));
		assertThat(watcher.announcedAmong(List.of(occupying(written)))).containsExactly(occupying(written));
		assertThat(watcher.announcedAmong(List.of(occupying(written)))).containsExactly(occupying(written));
	}

	/**
	 * A watcher that starts later - the application restarted while the worker
	 * kept going - reads the same rows. Nothing about recognising a write depends
	 * on having been running when it was announced.
	 */
	@Test
	void isReadableByAnInstanceThatDidNotExistWhenItWasAnnounced(@TempDir Path folder) throws IOException {
		Path written = folder.resolve("announced-before-the-restart.mp4");

		writer.occupy(null, () -> { }, written);

		assertThat(registry().announcedAmong(List.of(occupying(written)))).containsExactly(occupying(written));
	}

	/**
	 * An entry nobody matched must not silence a path forever, and the ceiling is
	 * read from the clock rather than from when the row happened to be written -
	 * so a watcher whose question comes late gets the honest answer, and the same
	 * path is a foreign change again.
	 */
	@Test
	void stopsRecognisingAWriteOnceItsCeilingHasPassed(@TempDir Path folder) throws IOException {
		Path written = folder.resolve("nobody-claimed-this.mp4");

		writer.occupy(null, () -> { }, written);

		SelfWrittenPathRegistry muchLater = registryAt(Instant.now().plus(Duration.ofHours(1)));

		assertThat(muchLater.announcedAmong(List.of(occupying(written)))).isEmpty();
	}

	/**
	 * The sweep runs on every announcement, so the table is bounded by what was
	 * announced inside one window rather than by how long the product has been
	 * running. It cannot cause a false negative either: what it removes is exactly
	 * what the question already refuses by age.
	 */
	@Test
	void sweepsWhatHasExpiredWithoutTouchingWhatIsStillLive(@TempDir Path folder) throws IOException {
		Path live = folder.resolve("still-being-written.mp4");

		writer.occupy(null, () -> { }, live);

		registryAt(Instant.now().plus(Duration.ofHours(1))).occupy(null, () -> { },
				folder.resolve("much-later.mp4"));

		assertThat(watcher.announcedAmong(List.of(occupying(live)))).isEmpty();
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
	void keepsRecognisingAWriteWhoseExecutionStillHoldsItsPaths(@TempDir Path folder) throws IOException {
		Path written = folder.resolve("very-large-move.mp4");

		SelfWrittenPathRegistry pastTheCeiling = registryAt(Instant.now().plus(Duration.ofHours(1)));

		// Asked from inside the copy, because that is when the answer matters and the
		// only time an execution holds anything: the announcement settles on the way
		// out, and from then on the ceiling is what decides.
		writer.occupy(runningExecutionLeasedFor(Duration.ofHours(3)), () -> assertThat(
				pastTheCeiling.announcedAmong(List.of(occupying(written)))).containsExactly(occupying(written)),
				written);
	}

	/**
	 * Bounded by the lease and not by the status, so a worker that died cannot
	 * leave a path silenced. Nothing renews the lease of a process that is gone,
	 * and the ceiling applies again the moment it lapses.
	 */
	@Test
	void stopsRecognisingItOnceTheExecutionsLeaseHasLapsed(@TempDir Path folder) throws IOException {
		Path written = folder.resolve("worker-died-mid-move.mp4");

		writer.occupy(runningExecutionLeasedFor(Duration.ofMinutes(1)), () -> { }, written);

		SelfWrittenPathRegistry pastTheCeiling = registryAt(Instant.now().plus(Duration.ofHours(1)));

		assertThat(pastTheCeiling.announcedAmong(List.of(occupying(written)))).isEmpty();
	}

	/**
	 * The sweep has to honour the same rule as the question, or housekeeping would
	 * quietly delete the row that a still-running move depends on.
	 */
	@Test
	void doesNotSweepAnEntryWhoseExecutionStillHoldsItsPaths(@TempDir Path folder) throws IOException {
		Path written = folder.resolve("still-being-moved.mp4");

		SelfWrittenPathRegistry pastTheCeiling = registryAt(Instant.now().plus(Duration.ofHours(1)));

		// The housekeeping runs while the move is still going, which is the case this
		// is about: any announcement of its own sweeps first.
		writer.occupy(runningExecutionLeasedFor(Duration.ofHours(3)), () -> {
			pastTheCeiling.occupy(null, () -> { }, folder.resolve("some-other-write.mp4"));

			assertThat(pastTheCeiling.announcedAmong(List.of(occupying(written))))
					.containsExactly(occupying(written));
		}, written);
	}

	/**
	 * Once the execution has ended, the entry falls back to the ceiling on its own
	 * - which is the margin the design asks for after a terminal state, arriving
	 * without anything having to remember to delete it.
	 */
	@Test
	void fallsBackToTheCeilingOnceTheExecutionHasEnded(@TempDir Path folder) throws IOException {
		Path written = folder.resolve("finished-move.mp4");

		Execution execution = execution(ExecutionStatus.RUNNING, Duration.ofHours(3));

		writer.occupy(execution.getId(), () -> { }, written);

		execution.setStatus(ExecutionStatus.FINISHED);

		executionRepository.save(execution);

		assertThat(watcher.announcedAmong(List.of(occupying(written)))).containsExactly(occupying(written));
		assertThat(registryAt(Instant.now().plus(Duration.ofHours(1))).announcedAmong(List.of(occupying(written)))).isEmpty();
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

	/**
	 * Emptying a path explains it going quiet and nothing else. A file appearing
	 * where one was moved away from is not what the operation announced, and a
	 * path just freed is a path somebody is likely to fill.
	 */
	@Test
	void vacatingDoesNotExplainSomethingArriving(@TempDir Path folder) throws IOException {
		Path emptied = folder.resolve("moved-away.mp4");

		writer.vacate(null, () -> { }, emptied);

		assertThat(watcher.announcedAmong(List.of(vacating(emptied)))).containsExactly(vacating(emptied));
		assertThat(watcher.announcedAmong(List.of(occupying(emptied)))).isEmpty();
	}

	/** And the other way: filling a path explains nothing about it vanishing. */
	@Test
	void occupyingDoesNotExplainSomethingBeingRemoved(@TempDir Path folder) throws IOException {
		Path placed = folder.resolve("just-placed.mp4");

		writer.occupy(null, () -> { }, placed);

		assertThat(watcher.announcedAmong(List.of(occupying(placed)))).containsExactly(occupying(placed));
		assertThat(watcher.announcedAmong(List.of(vacating(placed)))).isEmpty();
	}

	/** A move is both halves, and the watcher has to be able to account for each. */
	@Test
	void aMoveAnnouncesBothHalvesUnderTheirOwnRoles(@TempDir Path folder) throws IOException {
		Path source = folder.resolve("from.mp4");
		Path target = folder.resolve("to.mp4");

		writer.move(null, () -> { }, source, target);

		assertThat(watcher.announcedAmong(List.of(vacating(source), occupying(target))))
				.containsExactlyInAnyOrder(vacating(source), occupying(target));

		// The halves are not interchangeable: neither end explains the other's role.
		assertThat(watcher.announcedAmong(List.of(occupying(source), vacating(target)))).isEmpty();
	}

	/** One path can be both at once - the destination of one move is the source of the next. */
	@Test
	void onePathCanCarryBothRolesAtOnce(@TempDir Path folder) throws IOException {
		Path middle = folder.resolve("in-the-middle.mp4");

		writer.move(null, () -> { }, folder.resolve("first.mp4"), middle);
		writer.move(null, () -> { }, middle, folder.resolve("last.mp4"));

		assertThat(watcher.announcedAmong(List.of(vacating(middle), occupying(middle))))
				.containsExactlyInAnyOrder(vacating(middle), occupying(middle));
	}

	/**
	 * An operation that never happened leaves nothing behind. Otherwise the user
	 * making that same move by hand a moment later would go unreported.
	 */
	@Test
	void anOperationThatFailedRevokesItsAnnouncement(@TempDir Path folder) {
		Path source = folder.resolve("never-moved.mp4");
		Path target = folder.resolve("never-arrived.mp4");

		assertThatThrownBy(() -> writer.move(null, () -> {
			throw new IOException("the disk said no");
		}, source, target)).isInstanceOf(IOException.class);

		assertThat(watcher.announcedAmong(List.of(vacating(source), occupying(target)))).isEmpty();
	}

	/**
	 * A settled announcement still answers - the notifications it explains are
	 * still arriving - but it has stopped belonging to the execution that made it.
	 */
	@Test
	void aSettledAnnouncementStillAnswersAndIsNoLongerHeldByItsExecution(@TempDir Path folder) throws IOException {
		Path placed = folder.resolve("settled.mp4");

		writer.occupy(runningExecutionLeasedFor(Duration.ofHours(3)), () -> { }, placed);

		assertThat(watcher.announcedAmong(List.of(occupying(placed)))).containsExactly(occupying(placed));

		// A sweep an hour out. Asserted over this entry rather than over how many rows
		// it removed: the table is shared with every other case in this class, so the
		// count is a number about the suite and not about this announcement.
		selfWrittenPathRepository.deleteExpired(LocalDateTime.now(ZoneOffset.UTC).plusHours(1),
				LocalDateTime.now(ZoneOffset.UTC));

		assertThat(watcher.announcedAmong(List.of(occupying(placed))))
				.as("no execution holds it any more, so the ceiling is what decides").isEmpty();
	}

	/**
	 * The spellings Windows treats as one place meet, because the key is the one
	 * the catalog itself is keyed by rather than a second answer invented here.
	 */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void windowsSpellingsOfOnePlaceMeet(@TempDir Path folder) throws IOException {
		Path announced = folder.resolve("Foto.JPG");

		writer.occupy(null, () -> { }, announced);

		assertThat(watcher.announcedAmong(List.of(occupying(folder.resolve("foto.jpg")))))
				.containsExactly(occupying(folder.resolve("foto.jpg")));
	}

	/** A poll that saw nothing asks nothing. */
	@Test
	void anEmptyPollAsksNothing() {
		assertThat(watcher.announcedAmong(List.of())).isEmpty();
	}

	/**
	 * A path this side says it is filling. Most of these are about whether an
	 * announcement crosses at all, so they ask under one role; the cases that are
	 * about the roles themselves name both.
	 */
	private static SelfWrittenPath occupying(Path path) {
		return new SelfWrittenPath(path, SelfWriteRole.OCCUPYING);
	}

	private static SelfWrittenPath vacating(Path path) {
		return new SelfWrittenPath(path, SelfWriteRole.VACATING);
	}
}