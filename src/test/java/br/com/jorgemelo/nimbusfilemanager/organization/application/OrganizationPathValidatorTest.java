package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;

class OrganizationPathValidatorTest {

	@TempDir
	Path tempDir;

	@Test
	void shouldAllowPathsInsideConfiguredRoots() throws Exception {
		Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
		Path monitored = Files.createDirectory(tempDir.resolve("monitored"));
		Path source = Files.createDirectory(monitored.resolve("camera"));
		Path target = workspace.resolve("organized");

		Assertions.assertThatCode(() -> validator(workspace, monitored).validate(source, target))
				.doesNotThrowAnyException();
	}

	@Test
	void shouldRejectSourceOrTargetOutsideConfiguredRoots() throws Exception {
		Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
		Path monitored = Files.createDirectory(tempDir.resolve("monitored"));
		Path outside = Files.createDirectory(tempDir.resolve("outside"));

		OrganizationPathValidator validator = validator(workspace, monitored);

		Path organizedInWorkspace = workspace.resolve("organized");
		Path organizedOutside = outside.resolve("organized");

		Assertions.assertThatThrownBy(() -> validator.validate(outside, organizedInWorkspace))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("de origem deve estar dentro");
		Assertions.assertThatThrownBy(() -> validator.validate(monitored, organizedOutside))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("de destino deve estar dentro");
	}

	@Test
	void shouldKeepRelationshipAndRequiredPathChecks() throws Exception {
		Path workspace = Files.createDirectory(tempDir.resolve("workspace"));

		OrganizationPathValidator validator = validator(workspace);

		Assertions.assertThatThrownBy(() -> validator.validate(null, workspace.resolve("target")))
				.hasMessage("Informe a pasta de origem.");
		Assertions.assertThatThrownBy(() -> validator.validate(workspace, null))
				.hasMessage("Informe a pasta de destino.");
		Assertions.assertThatThrownBy(() -> validator.validate(workspace, workspace))
				.hasMessageContaining("devem ser diferentes");
		Assertions.assertThatThrownBy(() -> validator.validate(workspace, workspace.resolve("nested")))
				.hasMessageContaining("dentro da pasta de origem");
	}

	@Test
	void shouldRejectUndoMovementOutsideConfiguredRoots() throws Exception {
		Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
		Path outside = Files.createDirectory(tempDir.resolve("outside"));

		Assertions
				.assertThatThrownBy(
						() -> validator(workspace).validateAllowed(outside.resolve("file.jpg"), "undo source"))
				.hasMessageContaining("de origem do desfazer deve estar dentro");
	}

	@Test
	void rejectsANullPathAndLabelsUndoTargetAndUnknownRoles() throws Exception {
		Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
		Path outside = Files.createDirectory(tempDir.resolve("outside"));

		OrganizationPathValidator validator = validator(workspace);

		Assertions.assertThatThrownBy(() -> validator.validateAllowed(null, "target"))
				.isInstanceOf(IllegalArgumentException.class);
		Assertions.assertThatThrownBy(() -> validator.validateAllowed(outside.resolve("file.jpg"), "undo target"))
				.hasMessageContaining("de destino do desfazer deve estar dentro");
		// Unknown role is echoed back unchanged (defensive default branch).
		Assertions.assertThatThrownBy(() -> validator.validateAllowed(outside.resolve("file.jpg"), "mystery role"))
				.hasMessageContaining("mystery role");
	}

	private OrganizationPathValidator validator(Path... roots) {
		WorkspaceManager workspace = mock(WorkspaceManager.class);
		AppSettingService settings = mock(AppSettingService.class);

		when(workspace.getWorkspacePath()).thenReturn(roots[0]);
		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, ""))
				.thenReturn(roots.length > 1 ? roots[1].toString() : "");

		return new OrganizationPathValidator(settings, workspace);
	}
}