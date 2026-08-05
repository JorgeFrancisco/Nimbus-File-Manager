package br.com.jorgemelo.nimbusfilemanager.update.application;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.UpdateProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the update check on its own daemon thread, the way every other periodic
 * task in this application does - the app has no Spring
 * {@code @EnableScheduling}, so a {@code @Scheduled} annotation here is
 * decoration that never fires. It was one, and the check silently never ran on
 * a timer: it only ever worked when somebody pressed the button.
 *
 * <p>
 * The first pass waits, because a start already spends its first minutes
 * fetching a database server and the external tools, and an update check has no
 * business competing with the things the application cannot work without.
 */
@Slf4j
@Service
@Profile(NimbusProfiles.APP)
public class UpdateCheckScheduler {

	private static final long INITIAL_DELAY_SECONDS = 120;

	private final UpdateCheckService updateCheckService;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "nimbus-file-manager-update-check");

		thread.setDaemon(true);

		return thread;
	});
	private volatile boolean shuttingDown;

	public UpdateCheckScheduler(UpdateCheckService updateCheckService, UpdateProperties updateProperties) {
		this.updateCheckService = updateCheckService;

		long period = Math.max(1, updateProperties.checkInterval().toSeconds());

		executor.scheduleWithFixedDelay(this::runOnce, INITIAL_DELAY_SECONDS, period, TimeUnit.SECONDS);
	}

	/**
	 * One check. Package-private so it can be exercised directly in tests without
	 * the scheduler.
	 */
	final void runOnce() {
		try {
			updateCheckService.check();
		} catch (Exception exception) {
			// Being offline is normal here, and the check already swallows that. What
			// reaches this point is unexpected, and must not kill the scheduled task -
			// scheduleWithFixedDelay stops forever on an escaping exception.
			if (shuttingDown || Thread.currentThread().isInterrupted()) {
				log.debug("Scheduled update check interrupted during shutdown", exception);
			} else {
				log.warn("Scheduled update check failed", exception);
			}
		}
	}

	@PreDestroy
	void shutdown() {
		shuttingDown = true;

		executor.shutdownNow();
	}
}