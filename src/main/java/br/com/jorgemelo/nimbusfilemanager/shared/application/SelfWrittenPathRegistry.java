package br.com.jorgemelo.nimbusfilemanager.shared.application;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Paths the application itself is writing and is about to catalogue. The
 * watcher would otherwise see its own output as a foreign change and answer a
 * single converted file with a full recursive inventory of the whole library -
 * measured at five complete scans of a 145k-file drive during one conversion
 * batch, every one of them finding nothing but the file the application had
 * just catalogued on its own.
 *
 * <p>
 * Two properties keep the suppression honest. It is <em>single use</em>: the
 * first matching event consumes the entry, so a later external change to the
 * same path is reported normally. And it <em>expires</em>: an entry nobody
 * claims is dropped after {@link #ENTRY_TTL}, so a write whose event never
 * arrives cannot silence that path forever.
 */
@Component
public class SelfWrittenPathRegistry {

	/**
	 * Long enough for the event to travel from the file system to the next poll,
	 * short enough that a stale entry cannot hide a real change for any meaningful
	 * time.
	 */
	private static final Duration ENTRY_TTL = Duration.ofMinutes(5);

	private final Map<String, Instant> written = new ConcurrentHashMap<>();
	private final Clock clock;

	public SelfWrittenPathRegistry(Clock clock) {
		this.clock = clock;
	}

	/**
	 * Announces a path the application is placing on disk. Called before the file
	 * lands, because the watcher can poll the event within milliseconds of the
	 * write - registering afterwards would lose the race.
	 */
	public void announce(Path path) {
		if (path == null) {
			return;
		}

		purgeExpired();

		written.put(key(path), clock.instant());
	}

	/**
	 * Whether this change came from the application itself, consuming the record
	 * when it did.
	 */
	public boolean consume(Path path) {
		if (path == null) {
			return false;
		}

		Instant recordedAt = written.remove(key(path));

		return recordedAt != null && !expired(recordedAt);
	}

	private void purgeExpired() {
		written.values().removeIf(this::expired);
	}

	private boolean expired(Instant recordedAt) {
		return recordedAt.isBefore(clock.instant().minus(ENTRY_TTL));
	}

	private String key(Path path) {
		return PathUtils.normalizeLower(path.toString());
	}
}