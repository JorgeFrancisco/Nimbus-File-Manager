package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.application.ApplicationShutdown;
import br.com.jorgemelo.nimbusfilemanager.shared.application.CoverageGenerated;

/**
 * Ends the run through Spring, the same way the tray's Exit does, so the
 * embedded PostgreSQL is stopped rather than left running.
 *
 * <p>
 * On a thread of its own, after a pause: whoever asked for this is usually a
 * request that still has to be answered, and a server that goes down before
 * writing its response leaves the person looking at a browser error instead of
 * at the reason their application is closing.
 */
@Component
public class SpringApplicationShutdown implements ApplicationShutdown {

	// Long enough for a response to be written and rendered, short enough that
	// nobody wonders whether the click worked.
	private static final long DELAY_MILLIS = 3000;

	private final ConfigurableApplicationContext applicationContext;

	public SpringApplicationShutdown(ConfigurableApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

	@Override
	@CoverageGenerated("Starting this thread ends the process, which in a test is the suite")
	public void endRun() {
		Thread closer = new Thread(this::exitAfterDelay, "nimbus-file-manager-shutdown");

		closer.setDaemon(false);
		closer.start();
	}

	@CoverageGenerated("Ends the process; whoever decides to call it is what the tests assert")
	private void exitAfterDelay() {
		try {
			Thread.sleep(DELAY_MILLIS);
		} catch (InterruptedException _) {
			Thread.currentThread().interrupt();

			return;
		}

		System.exit(SpringApplication.exit(applicationContext, () -> 0));
	}
}