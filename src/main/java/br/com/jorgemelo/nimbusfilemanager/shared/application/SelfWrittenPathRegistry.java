package br.com.jorgemelo.nimbusfilemanager.shared.application;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

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
 * last write are reported separately, and the parser keeps only the path - and a
 * write that goes on for minutes spreads them over successive polls. Taking the
 * first one left every later notification looking foreign, which is the burst
 * this exists to prevent.
 *
 * <p>
 * What that trades away, said plainly: an <em>external</em> change to a file
 * while this product is writing that same file is suppressed. It needs somebody
 * to edit exactly the file being written at that moment, and the reconcile that
 * follows finds it anyway. On the other side of the scale is the measured
 * defect. Outside the window nothing is hidden.
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
	 * Announces a path this product is placing on disk, or taking off it. Called
	 * before the change lands, because the watcher can poll the event within
	 * milliseconds of the write - announcing afterwards would lose the race.
	 *
	 * <p>
	 * The expired are swept here, on the way past, which is where the map did it
	 * too. What makes an old entry harmless is the age filter on the question;
	 * this only keeps the table from growing.
	 */
	public void announce(Path path) {
		announce(path, null);
	}

	/**
	 * Announces a path being written on behalf of an execution.
	 *
	 * <p>
	 * The execution is what lets a write outlive the ceiling. A single move of a
	 * very large file across volumes can take longer than five minutes on its own,
	 * and the notifications for it keep arriving the whole time; an entry that
	 * expired underneath would turn the second half of one file's own write into a
	 * foreign change. So an entry that belongs to an execution still holding a live
	 * lease keeps answering, and the ceiling applies again the moment that stops
	 * being true - which is the existing possession, not a second clock invented to
	 * track the same thing.
	 *
	 * @param executionId the execution this write belongs to, or {@code null} for a
	 * write nobody queued - an Explorer rename, a folder swept after organising
	 */
	public void announce(Path path, Long executionId) {
		if (path == null) {
			return;
		}

		LocalDateTime now = LocalDateTime.now(clock);

		selfWrittenPathRepository.deleteExpired(now.minus(ENTRY_TTL), now);

		selfWrittenPathRepository.announce(key(path), executionId, now);
	}

	/**
	 * Which of these changes this product made itself.
	 *
	 * <p>
	 * Asked about a whole poll at once rather than one path at a time: the watcher
	 * hands over everything it saw this round, and one question costs one round
	 * trip whatever the answer is.
	 */
	public Set<Path> announcedAmong(Collection<Path> paths) {
		if (paths.isEmpty()) {
			return Set.of();
		}

		List<Path> candidates = List.copyOf(paths);

		LocalDateTime now = LocalDateTime.now(clock);

		Set<String> announced = selfWrittenPathRepository.announcedAmong(candidates.stream().map(this::key).toList(),
				now.minus(ENTRY_TTL), now);

		return candidates.stream().filter(path -> announced.contains(key(path)))
				.collect(Collectors.toUnmodifiableSet());
	}

	private String key(Path path) {
		return PathUtils.normalizeLower(path.toString());
	}
}