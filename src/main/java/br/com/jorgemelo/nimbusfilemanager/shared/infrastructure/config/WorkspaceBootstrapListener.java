package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.WorkspaceFolders;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WorkspaceBootstrapListener implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

	private static final String NIMBUS_FILE_MANAGER_WORKSPACE_FOLDERS = "nimbus-file-manager.workspace-folders";

	private static final String NIMBUS_FILE_MANAGER_WORKSPACE = "nimbus-file-manager.workspace";

	private static final String DEFAULT_WORKSPACE = "./workspace";

	private static final String DEFAULT_WORKSPACE_FOLDERS = String.join(",", WorkspaceFolders.DATABASE,
			WorkspaceFolders.LOGS, WorkspaceFolders.EXPORTS, WorkspaceFolders.TEMP, WorkspaceFolders.BACKUP);

	/**
	 * Runs after {@link LoggingApplicationListener}, which answers the same event.
	 * Logging before it meant writing into a log file that the definitive Logback
	 * configuration was about to replace: reconfiguring recreates the rolling file
	 * appender, and an appender starting over a file that already has content rolls
	 * it over - so every start that won this race archived a one-line file and
	 * burned an index the size limit had nothing to do with.
	 */
	@Override
	public int getOrder() {
		return LoggingApplicationListener.DEFAULT_ORDER + 1;
	}

	@Override
	public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
		Environment environment = event.getEnvironment();

		Path workspacePath = PathUtils
				.normalizePath(environment.getProperty(NIMBUS_FILE_MANAGER_WORKSPACE, DEFAULT_WORKSPACE));

		log.info("Initializing Nimbus File Manager workspace: {}", workspacePath);

		try {
			Files.createDirectories(workspacePath);

			for (String dir : getWorkspaceFolders(environment)) {
				Path folder = workspacePath.resolve(dir).normalize();

				Files.createDirectories(folder);

				log.debug("Ensured workspace folder: {}", folder);
			}

			log.info("Workspace initialization completed.");
		} catch (IOException e) {
			throw new IllegalStateException("Could not initialize workspace: " + workspacePath, e);
		}
	}

	private List<String> getWorkspaceFolders(Environment environment) {
		return Arrays.stream(
				environment.getProperty(NIMBUS_FILE_MANAGER_WORKSPACE_FOLDERS, DEFAULT_WORKSPACE_FOLDERS).split(","))
				.map(String::trim).filter(folder -> !folder.isBlank()).distinct().toList();
	}
}