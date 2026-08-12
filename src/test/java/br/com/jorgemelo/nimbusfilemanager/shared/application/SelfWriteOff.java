package br.com.jorgemelo.nimbusfilemanager.shared.application;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Collection;
import java.util.Set;

import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SelfWrittenPath;

/**
 * A registry for the tests that are not about self-write.
 *
 * <p>
 * Most of the code that changes a file on disk goes through the registry, so
 * almost every test of such a capability needs one to exist - and almost none of
 * them are asking anything about it. What they need is for the effect to happen.
 *
 * <p>
 * So this runs the effect and announces nothing. It is deliberately not a fake
 * of the real thing: recognising an announcement means canonicalizing a path
 * under its flavor and matching it by role, which lives in the database, and a
 * double that reimplemented it would be a second authority on when two paths are
 * one - the exact thing this work removed from production. The tests that ask
 * that question ask it of PostgreSQL, in the self-write integration test.
 */
public class SelfWriteOff extends SelfWrittenPathRegistry {

	public SelfWriteOff() {
		super(null, Clock.systemUTC());
	}

	@Override
	public void move(Long executionId, SelfWriteAction effect, Path from, Path to) throws IOException {
		effect.run();
	}

	@Override
	public void vacate(Long executionId, SelfWriteAction effect, Path path) throws IOException {
		effect.run();
	}

	@Override
	public void occupy(Long executionId, SelfWriteAction effect, Path path) throws IOException {
		effect.run();
	}

	/**
	 * Nothing here was announced, so nothing seen on disk is explained by it.
	 *
	 * <p>
	 * Overridden rather than inherited because the real one asks the database,
	 * which this double does not have - and a watcher that polls a folder asks
	 * this on every round, not only when something was written.
	 */
	@Override
	public Set<SelfWrittenPath> announcedAmong(Collection<SelfWrittenPath> claims) {
		return Set.of();
	}
}