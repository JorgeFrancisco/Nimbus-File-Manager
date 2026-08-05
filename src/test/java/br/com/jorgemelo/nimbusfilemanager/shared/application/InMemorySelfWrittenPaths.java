package br.com.jorgemelo.nimbusfilemanager.shared.application;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.SelfWrittenPathRepository;

/**
 * The self-written path table, kept in a map, for the unit tests of everything
 * that announces or reads announcements without being about the database.
 *
 * <p>
 * It behaves exactly as the real one does - announcing again renews, questions
 * are answered by age and never consume - so a test that passes here is testing
 * the same rule. What it does not prove is the part that matters most, that two
 * processes see each other; that needs a real PostgreSQL and has its own test.
 */
public class InMemorySelfWrittenPaths extends SelfWrittenPathRepository {

	private final Map<String, LocalDateTime> announced = new HashMap<>();

	public InMemorySelfWrittenPaths() {
		super(null);
	}

	@Override
	public void announce(String pathKey, Long executionId, LocalDateTime announcedAt) {
		announced.put(pathKey, announcedAt);
	}

	/**
	 * The lease side of the real question is deliberately absent: there is no
	 * execution table here to ask, so this answers by age alone. What that means
	 * for a test using it is that an announcement outliving the ceiling because its
	 * execution still holds its paths is not something this can show - that belongs
	 * to the integration test against a real database, and is asserted there.
	 */
	@Override
	public Set<String> announcedAmong(Collection<String> pathKeys, LocalDateTime notBefore, LocalDateTime now) {
		return pathKeys.stream().filter(pathKey -> isLive(pathKey, notBefore)).collect(Collectors.toSet());
	}

	@Override
	public int deleteExpired(LocalDateTime expiredBefore, LocalDateTime now) {
		int before = announced.size();

		announced.values().removeIf(at -> !at.isAfter(expiredBefore));

		return before - announced.size();
	}

	private boolean isLive(String pathKey, LocalDateTime notBefore) {
		LocalDateTime at = announced.get(pathKey);

		return at != null && at.isAfter(notBefore);
	}
}