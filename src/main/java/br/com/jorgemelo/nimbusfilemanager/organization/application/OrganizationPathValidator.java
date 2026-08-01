package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;

@Component
public class OrganizationPathValidator extends LocalizedComponent {

	private final AppSettingService appSettingService;
	private final WorkspaceManager workspaceManager;

	public OrganizationPathValidator(AppSettingService appSettingService, WorkspaceManager workspaceManager) {
		this.appSettingService = appSettingService;
		this.workspaceManager = workspaceManager;
	}

	public void validate(Path source, Path target) {
		if (source == null) {
			throw new IllegalArgumentException(message("backend.organization.sourceRequired"));
		}

		if (target == null) {
			throw new IllegalArgumentException(message("backend.organization.targetRequired"));
		}

		Path normalizedSource = source.toAbsolutePath().normalize();

		Path normalizedTarget = target.toAbsolutePath().normalize();

		validateAllowed(normalizedSource, "source");
		validateAllowed(normalizedTarget, "target");

		if (normalizedSource.equals(normalizedTarget)) {
			throw new IllegalArgumentException(message("backend.organization.pathsMustDiffer"));
		}

		if (normalizedTarget.startsWith(normalizedSource)) {
			throw new IllegalArgumentException(message("backend.organization.targetInsideSource"));
		}
	}

	void validateAllowed(Path path, String description) {
		if (path == null) {
			throw new IllegalArgumentException(message("backend.organization.pathRequired", roleLabel(description)));
		}

		Path resolvedPath = resolveExistingAncestor(path.toAbsolutePath().normalize());

		boolean allowed = allowedRoots().stream().map(this::resolveExistingAncestor).anyMatch(resolvedPath::startsWith);

		if (!allowed) {
			throw new IllegalArgumentException(
					message("backend.organization.pathOutsideRoots", roleLabel(description), path));
		}
	}

	/**
	 * Maps the technical path role passed by callers to its localized label, so the
	 * surrounding message reads naturally in every language. Unknown roles are
	 * echoed back unchanged as a defensive fallback.
	 */
	private String roleLabel(String description) {
		return switch (description) {
		case "source" -> message("backend.organization.roleSource");
		case "target" -> message("backend.organization.roleTarget");
		case "undo source" -> message("backend.organization.roleUndoSource");
		case "undo target" -> message("backend.organization.roleUndoTarget");
		default -> description;
		};
	}

	private List<Path> allowedRoots() {
		List<Path> roots = new ArrayList<>();

		roots.add(workspaceManager.getWorkspacePath());

		String monitoredFolder = appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "");

		if (monitoredFolder != null && !monitoredFolder.isBlank()) {
			roots.add(Path.of(monitoredFolder));
		}

		return roots;
	}

	/**
	 * Resolves symlinks in the existing part of a path. For a destination that does
	 * not exist yet, the nearest existing ancestor is canonicalized and the
	 * remaining path is appended to it.
	 */
	private Path resolveExistingAncestor(Path path) {
		Path absolute = path.toAbsolutePath().normalize();
		Path existing = absolute;

		while (existing != null && !Files.exists(existing)) {
			existing = existing.getParent();
		}

		if (existing == null) {
			return absolute;
		}

		try {
			Path realExisting = existing.toRealPath();

			return realExisting.resolve(existing.relativize(absolute)).normalize();
		} catch (IOException exception) {
			throw new IllegalArgumentException(message("backend.organization.pathResolveFailed", path), exception);
		}
	}
}