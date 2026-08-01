package br.com.jorgemelo.nimbusfilemanager.security.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;

/**
 * The one-time credential of a new installation. What matters is that two
 * installations never get the same password and that the operator has a way of
 * reading it - the file exists because a service with no console shows nothing.
 */
class FirstAccessCredentialTest {

	private final WorkspaceManager workspaceManager = mock(WorkspaceManager.class);

	private FirstAccessCredential credential(Path workspace) {
		when(workspaceManager.resolve("first-access.txt")).thenReturn(workspace.resolve("first-access.txt"));

		return new FirstAccessCredential(workspaceManager);
	}

	@Test
	void generatesADifferentPasswordEveryTime(@TempDir Path workspace) {
		FirstAccessCredential credential = new FirstAccessCredential(workspaceManager);

		String first = credential.generate();
		String second = credential.generate();

		Assertions.assertThat(first).isNotEqualTo(second).hasSizeGreaterThanOrEqualTo(16);
		Assertions.assertThat(workspace).exists();
	}

	@Test
	void writesTheCredentialWhereAnOperatorWithNoConsoleCanReadIt(@TempDir Path workspace) throws IOException {
		credential(workspace).publish("admin@example.com", "S3cr3t-Value");

		Path file = workspace.resolve("first-access.txt");

		Assertions.assertThat(file).exists();
		Assertions.assertThat(Files.readString(file)).contains("admin@example.com").contains("S3cr3t-Value")
				.contains("Delete this file");
	}

	/**
	 * A workspace that cannot be written to must not stop the start: the password
	 * is in the log as well, and an installation nobody can enter would be worse
	 * than one whose credential is inconvenient to find.
	 */
	@Test
	void survivesAWorkspaceItCannotWriteTo(@TempDir Path workspace) throws IOException {
		Path blocked = Files.createFile(workspace.resolve("blocked"));

		when(workspaceManager.resolve("first-access.txt")).thenReturn(blocked.resolve("first-access.txt"));

		FirstAccessCredential credential = new FirstAccessCredential(workspaceManager);

		Assertions.assertThatCode(() -> credential.publish("admin@example.com", "S3cr3t")).doesNotThrowAnyException();
	}
}