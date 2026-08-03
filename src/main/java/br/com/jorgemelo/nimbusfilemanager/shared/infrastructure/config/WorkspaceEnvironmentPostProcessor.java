package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config;

import static br.com.jorgemelo.nimbusfilemanager.database.application.constants.WorkspaceConstants.WORKSPACE_PROPERTY;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import br.com.jorgemelo.nimbusfilemanager.shared.application.WorkspaceLocation;

/**
 * Publishes the workspace path decided by {@link WorkspaceLocation} as the
 * property everything else reads.
 *
 * <p>
 * An {@code EnvironmentPostProcessor} rather than a line in {@code main}: this
 * runs for every context - a test slice included - and before Logback is
 * configured, which is the only window where the value can still reach the log
 * file's own path. Deciding it in {@code main} left every
 * {@code @SpringBootTest} without a workspace at all.
 *
 * <p>
 * The source is added last, so it loses to anything explicit: an environment
 * variable, a command-line flag, a test property.
 */
public class WorkspaceEnvironmentPostProcessor implements EnvironmentPostProcessor {

	private static final String SOURCE_NAME = "nimbusFileManagerWorkspace";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (environment.containsProperty(WORKSPACE_PROPERTY)) {
			return;
		}

		environment.getPropertySources()
				.addLast(new MapPropertySource(SOURCE_NAME, Map.of(WORKSPACE_PROPERTY, WorkspaceLocation.resolve())));
	}
}