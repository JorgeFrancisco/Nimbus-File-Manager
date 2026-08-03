package br.com.jorgemelo.nimbusfilemanager.update.application;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.AsyncConfig;
import br.com.jorgemelo.nimbusfilemanager.update.application.constants.UpdateMessages;
import br.com.jorgemelo.nimbusfilemanager.update.domain.enums.UpdateOutcome;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the update install in the background, so the request returns before the
 * work does.
 *
 * <p>
 * It used to be synchronous, and the installer is over a hundred megabytes: the
 * browser waited about a minute on a POST with nothing on screen, and the page
 * only came back when the download was already over - by which point the answer
 * was pointless, because the application was three seconds from closing. Lives
 * in its own bean so the {@code @Async} proxy is honored.
 *
 * <p>
 * One install at a time. A second click while the first is downloading would
 * fetch the same file twice into the same folder, and the loser of that race
 * would be verifying bytes the winner was still writing.
 */
@Slf4j
@Service
public class UpdateInstallAsyncRunner extends LocalizedComponent {

	private static final Map<UpdateOutcome, String> MESSAGES = Map.of(UpdateOutcome.NOTHING_TO_INSTALL,
			UpdateMessages.NOTHING_TO_INSTALL, UpdateOutcome.UNSUPPORTED_PLATFORM,
			UpdateMessages.UNSUPPORTED_PLATFORM, UpdateOutcome.DOWNLOAD_FAILED, UpdateMessages.DOWNLOAD_FAILED,
			UpdateOutcome.CHECKSUM_UNAVAILABLE, UpdateMessages.CHECKSUM_UNAVAILABLE, UpdateOutcome.CHECKSUM_MISMATCH,
			UpdateMessages.CHECKSUM_MISMATCH, UpdateOutcome.COULD_NOT_START, UpdateMessages.COULD_NOT_START);

	private final UpdateInstallService installService;
	private final UpdateInstallProgress progress;
	private final AtomicBoolean running = new AtomicBoolean(false);

	public UpdateInstallAsyncRunner(UpdateInstallService installService, UpdateInstallProgress progress) {
		this.installService = installService;
		this.progress = progress;
	}

	/**
	 * Claims the install for this caller.
	 *
	 * @return false when one is already running, which the screen reports rather
	 * than starting a second download of the same file
	 */
	public boolean start() {
		if (!running.compareAndSet(false, true)) {
			return false;
		}

		progress.start();

		return true;
	}

	public boolean isRunning() {
		return running.get();
	}

	@Async(AsyncConfig.TASK_EXECUTOR)
	public void install() {
		UpdateOutcome outcome = UpdateOutcome.COULD_NOT_START;

		try {
			outcome = installService.install();

			if (outcome != UpdateOutcome.STARTED) {
				progress.failed(message(MESSAGES.get(outcome)));
			}
		} catch (Exception exception) {
			log.warn("The update install failed unexpectedly", exception);

			progress.failed(message(UpdateMessages.COULD_NOT_START));
		} finally {
			// Stays claimed once the installer is running: this run is ending, and a
			// second attempt would only race the shutdown. Any other ending frees it,
			// so a failure can be retried without restarting the application.
			if (outcome != UpdateOutcome.STARTED) {
				running.set(false);
			}
		}
	}
}