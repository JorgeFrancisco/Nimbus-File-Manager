package br.com.jorgemelo.nimbusfilemanager.shared.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The register that lets the folder watcher tell its own product's writes apart
 * from a change made behind its back.
 *
 * <p>
 * Announcements are looked at, not taken - which is a change from the single-use
 * rule this used to have, and the reason is what the watcher does with one
 * write. The name, the size and the last write are reported separately, only the
 * path survives the parser, and a write lasting minutes spreads them over
 * successive polls. Taking the first one left every later notification looking
 * foreign, which is the burst of full inventories the register exists to
 * prevent.
 */
class SelfWrittenPathRegistryTest {

	private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-07-26T23:00:00Z"));
	private final Clock clock = mock(Clock.class);

	SelfWrittenPathRegistryTest() {
		when(clock.instant()).thenAnswer(_ -> now.get());
		when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		when(clock.withZone(ZoneId.of("UTC"))).thenReturn(clock);
	}

	/**
	 * The case single use got wrong. One write is several notifications, arriving
	 * over more than one poll, and every one of them has to be recognised.
	 */
	@Test
	void keepsRecognisingTheSameWriteAcrossRepeatedNotifications(@TempDir Path folder) {
		Path written = folder.resolve("clip.mp4");

		SelfWrittenPathRegistry registry = registry();

		registry.announce(written);

		Assertions.assertThat(registry.announcedAmong(List.of(written))).containsExactly(written);
		Assertions.assertThat(registry.announcedAmong(List.of(written))).containsExactly(written);
	}

	/** A path this product never wrote was changed by someone else. */
	@Test
	void reportsAPathItNeverSawAsAForeignChange(@TempDir Path folder) {
		SelfWrittenPathRegistry registry = registry();

		registry.announce(folder.resolve("mine.mp4"));

		Assertions.assertThat(registry.announcedAmong(List.of(folder.resolve("theirs.mp4")))).isEmpty();
	}

	/** Both ends of a move, which is what a rename produces notifications for. */
	@Test
	void answersAboutAWholePollAtOnce(@TempDir Path folder) {
		Path source = folder.resolve("before.mp4");
		Path target = folder.resolve("after.mp4");
		Path foreign = folder.resolve("theirs.mp4");

		SelfWrittenPathRegistry registry = registry();

		registry.announce(source);
		registry.announce(target);

		Assertions.assertThat(registry.announcedAmong(List.of(source, target, foreign)))
				.containsExactlyInAnyOrder(source, target);
	}

	/**
	 * A directory, which is what an Explorer rename of a folder and a sweep of an
	 * empty one leave behind: a delete is reported without being inspected,
	 * because a path already gone cannot be.
	 */
	@Test
	void recognisesADirectoryTheSameWayAsAFile(@TempDir Path folder) {
		Path removed = folder.resolve("empty-folder");

		SelfWrittenPathRegistry registry = registry();

		registry.announce(removed);

		Assertions.assertThat(registry.announcedAmong(List.of(removed))).containsExactly(removed);
	}

	/**
	 * An entry nobody ever matched - the notification was lost, or the write never
	 * produced one - must not silence that path forever.
	 */
	@Test
	void stopsSuppressingARecordNothingMatchedWithinItsLifetime(@TempDir Path folder) {
		Path written = folder.resolve("clip.mp4");

		SelfWrittenPathRegistry registry = registry();

		registry.announce(written);

		now.set(now.get().plus(Duration.ofMinutes(30)));

		Assertions.assertThat(registry.announcedAmong(List.of(written))).isEmpty();
	}

	/**
	 * Announcing again pushes the ceiling out, which is what a write that goes on
	 * for minutes depends on - and it is why the lifetime is a ceiling rather than
	 * a countdown from the first announcement.
	 */
	@Test
	void renewsTheCeilingEachTimeTheSamePathIsAnnounced(@TempDir Path folder) {
		Path written = folder.resolve("long-encode.mp4");

		SelfWrittenPathRegistry registry = registry();

		registry.announce(written);

		now.set(now.get().plus(Duration.ofMinutes(4)));

		registry.announce(written);

		now.set(now.get().plus(Duration.ofMinutes(4)));

		Assertions.assertThat(registry.announcedAmong(List.of(written))).containsExactly(written);
	}

	@Test
	void toleratesANullPathAndAnEmptyPoll() {
		SelfWrittenPathRegistry registry = registry();

		registry.announce(null);

		Assertions.assertThat(registry.announcedAmong(List.of())).isEmpty();
	}

	private SelfWrittenPathRegistry registry() {
		return new SelfWrittenPathRegistry(new InMemorySelfWrittenPaths(), clock);
	}
}