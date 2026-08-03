package br.com.jorgemelo.nimbusfilemanager.update.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.update.application.dto.PublishedRelease;

/**
 * Holds the answer the tray and the settings screen read. What it remembers
 * matters as much as what it finds: an installation that has just been updated
 * must stop being told to update, and one that is behind must keep being told
 * until it is not.
 */
class UpdateCheckServiceTest {

	private static final Clock FIXED = Clock.fixed(Instant.parse("2026-08-03T15:30:00Z"), ZoneOffset.UTC);

	@Test
	void findsNothingWhenTheRunHasNoVersionOfItsOwn() {
		UpdateCheckService service = check(counting(new AtomicInteger()));

		Assertions.assertThat(service.check()).isEmpty();
		Assertions.assertThat(service.available()).isEmpty();
	}

	/**
	 * The check must not reach the network before it knows there is something to
	 * compare against: a developer machine should never contact the endpoint.
	 */
	@Test
	void doesNotAskAboutReleasesWithoutAVersionToCompare() {
		AtomicInteger asked = new AtomicInteger();

		check(counting(asked)).check(null);
		check(counting(asked)).check("  ");

		Assertions.assertThat(asked).hasValue(0);
	}

	@Test
	void hasNothingToShowBeforeTheFirstCheck() {
		Assertions.assertThat(check(counting(new AtomicInteger())).available()).isEmpty();
	}

	@Test
	void remembersAReleaseThatSupersedesTheInstalledVersion() {
		UpdateCheckService service = check(publishing("v6.1.0.160"));

		Assertions.assertThat(service.check("6.0.0.147")).isPresent();
		Assertions.assertThat(service.available()).isPresent();
		Assertions.assertThat(service.available().orElseThrow().published()).isEqualTo("v6.1.0.160");
		Assertions.assertThat(service.available().orElseThrow().installed()).isEqualTo("6.0.0.147");
	}

	@Test
	void remembersNothingWhenTheInstallationIsCurrent() {
		UpdateCheckService service = check(publishing("v6.1.0.160"));

		Assertions.assertThat(service.check("6.1.0.160")).isEmpty();
		Assertions.assertThat(service.available()).isEmpty();
	}

	/**
	 * The reason the remembered value is cleared rather than only set. An
	 * installation that took the update and restarted would otherwise keep offering
	 * the version it is already running.
	 */
	@Test
	void forgetsWhatItFoundOnceTheInstallationCatchesUp() {
		UpdateCheckService service = check(publishing("v6.1.0.160"));

		service.check("6.0.0.147");

		Assertions.assertThat(service.available()).isPresent();

		service.check("6.1.0.160");

		Assertions.assertThat(service.available()).isEmpty();
	}

	/**
	 * Being offline is the normal state of a local-first application, and it must
	 * not erase what a previous check found.
	 */
	@Test
	void keepsWhatItFoundWhenALaterCheckCannotReachAnything() {
		ReleaseSource flaky = new ReleaseSource() {

			private int calls;

			@Override
			public Optional<PublishedRelease> latest() {
				return calls++ == 0 ? Optional.of(release("v6.1.0.160")) : Optional.empty();
			}
		};

		UpdateCheckService service = check(flaky);

		service.check("6.0.0.147");
		service.check("6.0.0.147");

		Assertions.assertThat(service.available()).isPresent();
	}

	@Test
	void hasNoLastCheckedTimeBeforeItEverAsked() {
		Assertions.assertThat(check(publishing("v6.1.0.160")).lastCheckedAt()).isNull();
	}

	@Test
	void recordsWhenItAsked() {
		UpdateCheckService service = at(FIXED, publishing("v6.1.0.160"));

		service.check("6.0.0.147");

		Assertions.assertThat(service.lastCheckedAt()).isEqualTo(LocalDateTime.now(FIXED));
	}

	/**
	 * Being offline is a normal state here, and a timestamp that only moved on
	 * success would sit frozen without saying why - which is exactly the doubt it
	 * exists to answer: is the check running, or is there simply nothing newer?
	 */
	@Test
	void recordsTheAttemptEvenWhenNothingCouldBeReached() {
		UpdateCheckService service = at(FIXED, counting(new AtomicInteger()));

		service.check("6.0.0.147");

		Assertions.assertThat(service.lastCheckedAt()).isEqualTo(LocalDateTime.now(FIXED));
	}

	/**
	 * A run with no version of its own never asks anything, so there is no attempt
	 * to record - stamping one would claim a check that did not happen.
	 */
	@Test
	void recordsNothingWhenItNeverAsked() {
		UpdateCheckService service = at(FIXED, counting(new AtomicInteger()));

		service.check(null);

		Assertions.assertThat(service.lastCheckedAt()).isNull();
	}

	private static UpdateCheckService at(Clock clock, ReleaseSource source) {
		return new UpdateCheckService(source, _ -> {
		}, clock);
	}

	private static UpdateCheckService check(ReleaseSource source) {
		return new UpdateCheckService(source, _ -> {
		}, Clock.systemDefaultZone());
	}

	private static ReleaseSource counting(AtomicInteger asked) {
		return () -> {
			asked.incrementAndGet();

			return Optional.<PublishedRelease>empty();
		};
	}

	private static ReleaseSource publishing(String tag) {
		return () -> Optional.of(release(tag));
	}

	private static PublishedRelease release(String tag) {
		return new PublishedRelease(tag, "https://example.invalid/page", "a.msi", "https://example.invalid/a.msi",
				"https://example.invalid/a.msi.sha256", 1024L);
	}
}