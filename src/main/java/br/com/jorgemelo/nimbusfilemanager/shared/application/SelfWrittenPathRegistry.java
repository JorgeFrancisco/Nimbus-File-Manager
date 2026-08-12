package br.com.jorgemelo.nimbusfilemanager.shared.application;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SelfWrittenPath;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.SelfWriteRole;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.SelfWrittenPathRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Paths this product is writing and is about to catalogue. The watcher would
 * otherwise see its own output as a foreign change and answer a single
 * converted file with a full recursive inventory of the whole library -
 * measured at five complete scans of a 145k-file drive during one conversion
 * batch, every one of them finding nothing but the file the application had
 * just catalogued on its own.
 *
 * <p>
 * It used to be a map, which was right while one process did both the writing
 * and the watching. It is a table now, for the plain reason that those became
 * two processes: the worker moves the file and the application's watcher sees it
 * arrive, and memory in either one answers only for itself. Nothing is cached in
 * front of it - a stale "nobody announced this" is precisely the answer that
 * must never be wrong, because it is the one that starts an inventory.
 *
 * <p>
 * Announcements are <em>looked at</em>, not taken. That is a deliberate change
 * from the single-use behaviour this class had, and the reason is in the
 * watcher: one write produces several notifications - the name, the size and the
 * last write are reported separately - and a write that goes on for minutes
 * spreads them over successive polls. Taking the first one left every later
 * notification looking foreign, which is the burst this exists to prevent.
 *
 * <p>
 * <b>The effect is run here.</b> Announcing is not something a caller does
 * before doing something else: an announcement whose write never happened
 * silences the next real change to those paths, and one whose write finished
 * long ago keeps silencing them for as long as its batch runs. Both were
 * possible while the two halves were separate calls, so there is now one call
 * that does both, and nothing outside this class can announce without saying
 * what for.
 */
@Component
public class SelfWrittenPathRegistry {

	/**
	 * The ceiling. Long enough for the tail of notifications from a slow write to
	 * arrive, short enough that an entry nobody claims cannot hide a real change
	 * for any meaningful time - and a ceiling rather than a lifetime, because
	 * announcing the same path again pushes it out.
	 */
	private static final Duration ENTRY_TTL = Duration.ofMinutes(5);

	private final SelfWrittenPathRepository selfWrittenPathRepository;
	private final Clock clock;

	public SelfWrittenPathRegistry(SelfWrittenPathRepository selfWrittenPathRepository, Clock clock) {
		this.selfWrittenPathRepository = selfWrittenPathRepository;
		this.clock = clock;
	}

	/**
	 * A file this product is taking from one path and putting at another.
	 *
	 * @param executionId the execution this write belongs to, or {@code null} for
	 * a write nobody queued - an Explorer rename, a folder swept after organising.
	 * Naming it is what lets a write that outlasts the ceiling - a very large file
	 * crossing volumes - go on being recognised for as long as the execution
	 * demonstrably still holds its paths
	 */
	public void move(Long executionId, SelfWriteAction effect, Path from, Path to) throws IOException {
		perform(executionId, effect, List.of(new SelfWrittenPath(from, SelfWriteRole.VACATING),
				new SelfWrittenPath(to, SelfWriteRole.OCCUPYING)));
	}

	/** A path this product is emptying: a file or an empty folder being removed. */
	public void vacate(Long executionId, SelfWriteAction effect, Path path) throws IOException {
		perform(executionId, effect, List.of(new SelfWrittenPath(path, SelfWriteRole.VACATING)));
	}

	/** A path this product is writing at without taking anything away from it. */
	public void occupy(Long executionId, SelfWriteAction effect, Path path) throws IOException {
		perform(executionId, effect, List.of(new SelfWrittenPath(path, SelfWriteRole.OCCUPYING)));
	}

	/**
	 * Which of these this product announced and is accountable for.
	 *
	 * <p>
	 * Asked about a whole poll at once rather than one path at a time: the watcher
	 * hands over everything it saw this round, and one question costs one round
	 * trip whatever the answer is.
	 */
	public Set<SelfWrittenPath> announcedAmong(Collection<SelfWrittenPath> claims) {
		if (claims.isEmpty()) {
			return Set.of();
		}

		List<SelfWrittenPath> candidates = List.copyOf(claims);

		LocalDateTime now = LocalDateTime.now(clock);

		Set<Integer> announced = selfWrittenPathRepository.announcedAmong(spellings(candidates), flavors(candidates),
				roles(candidates), now.minus(ENTRY_TTL), now);

		return IntStream.range(0, candidates.size()).filter(index -> announced.contains(index + 1))
				.mapToObj(candidates::get).collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * Announces, makes the change, and closes the announcement according to what
	 * happened.
	 *
	 * <p>
	 * Announced before the change lands, because the watcher can poll the event
	 * within milliseconds of the write - announcing afterwards loses the race. The
	 * expired are swept on the way past, which is where the map did it too; what
	 * makes an old entry harmless is the age filter on the question, and this only
	 * keeps the table from growing.
	 */
	private void perform(Long executionId, SelfWriteAction effect, List<SelfWrittenPath> claims) throws IOException {
		LocalDateTime announcedAt = LocalDateTime.now(clock);

		selfWrittenPathRepository.deleteExpired(announcedAt.minus(ENTRY_TTL), announcedAt);

		selfWrittenPathRepository.announce(spellings(claims), flavors(claims), roles(claims), executionId,
				announcedAt);

		try {
			effect.run();
		} catch (IOException | RuntimeException failure) {
			// Nothing landed, so nothing of ours is on its way. An announcement left
			// standing here is a window in which the user doing this very move by hand
			// goes unreported - and the paths a batch failed on are the ones somebody is
			// most likely to touch next.
			selfWrittenPathRepository.revoke(spellings(claims), flavors(claims), roles(claims), announcedAt);

			throw failure;
		}

		settle(claims, announcedAt);
	}

	/**
	 * The write happened, so the entry stops belonging to the execution and starts
	 * counting its ceiling from now.
	 *
	 * <p>
	 * Both halves matter and they are the same fix. The notifications still on
	 * their way are ours - including any a caller's rollback produces - so the
	 * entry cannot simply go; but a batch that moves ten thousand files runs for
	 * hours, and an entry held alive by its execution silences those paths for the
	 * whole of it. The end of the write is the honest anchor for the ceiling:
	 * after it, what is left to arrive is a tail, not a write.
	 */
	private void settle(List<SelfWrittenPath> claims, LocalDateTime announcedAt) {
		selfWrittenPathRepository.settle(spellings(claims), flavors(claims), roles(claims), announcedAt,
				LocalDateTime.now(clock));
	}

	/**
	 * The paths as this process reads them, absolute and resolved but otherwise
	 * spelled as they are. Folding case or separators is the database's job and is
	 * done there by the function the catalog itself is keyed by - doing any of it
	 * here would be a second authority on when two paths are one, which on POSIX
	 * gets a different answer.
	 */
	private static String[] spellings(List<SelfWrittenPath> claims) {
		return claims.stream().map(claim -> PathUtils.normalize(claim.path())).toArray(String[]::new);
	}

	private static String[] flavors(List<SelfWrittenPath> claims) {
		return claims.stream().map(claim -> PathFlavor.of(claim.path()).name()).toArray(String[]::new);
	}

	private static String[] roles(List<SelfWrittenPath> claims) {
		return claims.stream().map(claim -> claim.role().name()).toArray(String[]::new);
	}
}