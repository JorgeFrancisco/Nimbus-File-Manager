package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config;

import java.nio.file.Path;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.WorkspaceFolders;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

@Component
public class WorkspaceManager {

	private final NimbusFileManagerProperties properties;

	public WorkspaceManager(NimbusFileManagerProperties properties) {
		this.properties = properties;
	}

	/**
	 * Where the application keeps what it owns: database lock files, logs,
	 * exports, backups, downloaded datasets. The value itself is decided once, at
	 * startup, by {@link WorkspaceLocation} - here it is only read, so this class
	 * can never disagree with the folder Logback is already writing into.
	 */
	public Path getWorkspacePath() {
		return PathUtils.normalizePath(properties.workspace());
	}

	public Path resolve(String... paths) {
		Path result = getWorkspacePath();

		for (String path : paths) {
			result = result.resolve(path);
		}

		return result.normalize();
	}

	public Path temp() {
		return resolve(WorkspaceFolders.TEMP);
	}

	public Path geodata() {
		return resolve(WorkspaceFolders.GEODATA);
	}
}