package br.com.jorgemelo.nimbusfilemanager.update.application;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.shared.application.InstalledVersion;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.AvailableUpdate;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.PublishedRelease;
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
	private static final String INTERVAL = "PT24H";

	private final ReleaseSource releaseSource;
	private final AtomicReference<AvailableUpdate> available = new AtomicReference<>();

	public UpdateCheckService(ReleaseSource releaseSource) {
		this.releaseSource = releaseSource;
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

		update.ifPresent(found -> log.info("Version {} is available; this installation is {}", found.published(),
				found.installed()));

		return update;
	}
}