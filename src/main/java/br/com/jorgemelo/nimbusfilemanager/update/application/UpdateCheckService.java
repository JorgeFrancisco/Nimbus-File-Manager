package br.com.jorgemelo.nimbusfilemanager.update.application;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.shared.application.InstalledVersion;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.AvailableUpdate;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.PublishedRelease;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.UpdateFound;
import lombok.extern.slf4j.Slf4j;

/**
 * Keeps the answer to "is there a newer version than this one?".
 *
 * <p>
 * Driven by {@link UpdateCheckScheduler} on a timer rather than only at
 * startup, because the installations this exists for are the ones left open for
 * weeks - a check that only happened at start would never fire on the machine
 * that most needs it. The scheduling lives there and not in an annotation here:
 * this application has no {@code @EnableScheduling}, so {@code @Scheduled}
 * never fires, and it silently did nothing until a real installation was left
 * running and nothing ever happened.
 *
 * <p>
 * A run that does not know its own version never asks anything. That is the IDE
 * and the Maven run, where there is no manifest, and where offering to replace
 * the installation would be offering to overwrite somebody's working copy.
 * Skipping before the request also means the developer machine never contacts
 * the endpoint at all.
 */
@Slf4j
@Service
public class UpdateCheckService {

	private final ReleaseSource releaseSource;
	private final ApplicationEventPublisher eventPublisher;
	private final Clock clock;
	private final AtomicReference<AvailableUpdate> available = new AtomicReference<>();
	private final AtomicReference<String> announced = new AtomicReference<>();
	private final AtomicReference<LocalDateTime> lastCheckedAt = new AtomicReference<>();

	public UpdateCheckService(ReleaseSource releaseSource, ApplicationEventPublisher eventPublisher, Clock clock) {
		this.releaseSource = releaseSource;
		this.eventPublisher = eventPublisher;
		this.clock = clock;
	}

	/**
	 * When the endpoint was last asked, or {@code null} when it never was.
	 *
	 * <p>
	 * On the screen because "no newer version" and "the check is not running" look
	 * identical otherwise, and only one of them is good news. It marks the moment
	 * the question was asked, including when the answer was that nothing could be
	 * reached - being offline is a normal state here, and a timestamp that only
	 * moved on success would freeze without explaining itself.
	 */
	public LocalDateTime lastCheckedAt() {
		return lastCheckedAt.get();
	}

	/**
	 * Once per version rather than once per check. The check runs every fifteen
	 * minutes, and a notification repeating the same sentence four times an hour
	 * is not a reminder - it is a reason to disable notifications.
	 */
	private void announce(String published) {
		if (!published.equals(announced.getAndSet(published))) {
			eventPublisher.publishEvent(new UpdateFound(published));
		}
	}

	/**
	 * What the last check found, for the tray and the screen to show. Empty until
	 * one has run, and empty again once an installation catches up.
	 */
	public Optional<AvailableUpdate> available() {
		return Optional.ofNullable(available.get());
	}

	public Optional<AvailableUpdate> check() {
		return check(InstalledVersion.current().orElse(null));
	}

	/**
	 * Package-private so the test can supply the version a packaged run would
	 * have. The suite has no manifest, so without this the only reachable
	 * behaviour would be the one that gives up before asking anything.
	 */
	Optional<AvailableUpdate> check(String installedVersion) {
		if (installedVersion == null || installedVersion.isBlank()) {
			return Optional.empty();
		}

		Optional<PublishedRelease> release = releaseSource.latest();

		lastCheckedAt.set(LocalDateTime.now(clock));

		if (release.isEmpty()) {
			return Optional.empty();
		}

		Optional<AvailableUpdate> update = UpdateAvailability.decide(installedVersion, release.get());

		// Cleared rather than kept when there is nothing to offer: an installation
		// that has just been updated must stop being told to update.
		available.set(update.orElse(null));

		update.ifPresent(found -> {
			log.info("Version {} is available; this installation is {}", found.published(), found.installed());

			announce(found.published());
		});

		return update;
	}
}