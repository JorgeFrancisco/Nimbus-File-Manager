package br.com.jorgemelo.nimbusfilemanager.shared.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The register that lets the folder watcher tell its own application's writes
 * apart from a change made behind its back.
 */
class SelfWrittenPathRegistryTest {

	private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-07-26T23:00:00Z"));
	private final Clock clock = mock(Clock.class);

	SelfWrittenPathRegistryTest() {
		when(clock.instant()).thenAnswer(_ -> now.get());
	}

	/**
	 * Suppression is single use on purpose: the write is announced once, and every
	 * later change to the same path is somebody else's and has to be reported.
	 */
	@Test
	void consumesTheRecordSoASecondChangeToTheSamePathIsReported(@TempDir Path folder) {
		Path written = folder.resolve("clip.mp4");

		SelfWrittenPathRegistry registry = new SelfWrittenPathRegistry(clock);

		registry.announce(written);

		Assertions.assertThat(registry.consume(written)).isTrue();
		Assertions.assertThat(registry.consume(written)).isFalse();
	}

	/** A path the application never wrote was changed by someone else. */
	@Test
	void reportsAPathItNeverSawAsAForeignChange(@TempDir Path folder) {
		SelfWrittenPathRegistry registry = new SelfWrittenPathRegistry(clock);

		registry.announce(folder.resolve("mine.mp4"));

		Assertions.assertThat(registry.consume(folder.resolve("theirs.mp4"))).isFalse();
	}

	/**
	 * An entry nobody ever claims - the event was lost, or the write never produced
	 * one - must not silence that path forever.
	 */
	@Test
	void stopsSuppressingARecordNobodyClaimedWithinItsLifetime(@TempDir Path folder) {
		Path written = folder.resolve("clip.mp4");

		SelfWrittenPathRegistry registry = new SelfWrittenPathRegistry(clock);

		registry.announce(written);

		now.set(now.get().plus(Duration.ofMinutes(30)));

		Assertions.assertThat(registry.consume(written)).isFalse();
	}

	/** Nothing to record and nothing to consume: never throws on a null path. */
	@Test
	void toleratesANullPathOnBothSides() {
		SelfWrittenPathRegistry registry = new SelfWrittenPathRegistry(clock);

		registry.announce(null);

		Assertions.assertThat(registry.consume(null)).isFalse();
	}
}