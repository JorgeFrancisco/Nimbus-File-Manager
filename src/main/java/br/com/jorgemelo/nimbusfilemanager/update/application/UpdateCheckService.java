package br.com.jorgemelo.nimbusfilemanager.update.application;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
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
 * It runs on a timer rather than only at startup, because the installations
 * this exists for are the ones left open for weeks - a check that only happened
 * at start would never fire on the machine that most needs it. The first run is
 * delayed: a start already spends its first minutes fetching a database server
 * and the external tools, and an update check has no business competing with
 * the things the application cannot work without.
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

	// Late enough that the bootstrap downloads are done competing for the network,
	// and long before anyone would think to look for an update notice.
	private static final String INITIAL_DELAY = "PT2M";
	// Fifteen minutes is four requests an hour against an endpoint that allows
	// sixty from an address that does not authenticate. A minute would sit exactly
	// on that ceiling, and the first thing to share the address - a browser, a
	// second installation - would push it over, after which the answer is a
	// refusal and updates stop being found without anything saying so.
	private static final String INTERVAL = "${nimbus-file-manager.update.check-interval:PT15M}";

	private final ReleaseSource releaseSource;
	private final ApplicationEventPublisher eventPublisher;
	private final AtomicReference<AvailableUpdate> available = new AtomicReference<>();
	private final AtomicReference<String> announced = new AtomicReference<>();

	public UpdateCheckService(ReleaseSource releaseSource, ApplicationEventPublisher eventPublisher) {
		this.releaseSource = releaseSource;
		this.eventPublisher = eventPublisher;
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

	@Scheduled(initialDelayString = INITIAL_DELAY, fixedDelayString = INTERVAL)
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