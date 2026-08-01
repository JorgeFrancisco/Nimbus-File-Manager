package br.com.jorgemelo.nimbusfilemanager.database.infrastructure.config;

import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

import br.com.jorgemelo.nimbusfilemanager.database.application.EmbeddedClusterService;

/**
 * Stops the cluster this run started, if it started one.
 *
 * <p>
 * On {@code ContextClosedEvent} rather than a shutdown hook of its own: by then
 * the connection pool has been closed with the rest of the beans, so the server
 * is asked to stop with nothing still talking to it. A hook would race the same
 * shutdown instead of following it.
 */
public class EmbeddedDatabaseShutdown implements ApplicationListener<ContextClosedEvent> {

	@Override
	public void onApplicationEvent(ContextClosedEvent event) {
		EmbeddedClusterService service = EmbeddedDatabaseBootstrap.running();

		if (service != null) {
			service.stop();
		}
	}
}