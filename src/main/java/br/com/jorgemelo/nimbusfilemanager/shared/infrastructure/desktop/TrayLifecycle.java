package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.desktop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * Hands the tray the two things only a running context knows: the port that was
 * actually bound, and a way to close the application the way it wants to be
 * closed.
 *
 * <p>
 * The shutdown matters more than it looks. Ending this process abruptly leaves
 * the embedded PostgreSQL running - it is stopped by a shutdown handler - so
 * "Exit" has to go through Spring rather than through {@code System.exit}.
 * Until this listener runs, the tray's own exit is a plain one, which is
 * correct: before the context there is no server to leave behind.
 */
@Component
class TrayLifecycle {

	private final ApplicationContext applicationContext;

	TrayLifecycle(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

	/**
	 * The bound port rather than the configured one: a port of 0, or one taken by
	 * something else, makes the two different, and the menu has to open the one
	 * that answers.
	 */
	@EventListener
	void onWebServerReady(WebServerInitializedEvent event) {
		ApplicationTray.ready(event.getWebServer().getPort(), this::shutdown);
	}

	@PreDestroy
	void onShutdown() {
		ApplicationTray.remove();
	}

	private void shutdown() {
		System.exit(SpringApplication.exit(applicationContext, () -> 0));
	}
}