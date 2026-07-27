package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;

/**
 * The work folder lives outside the library now, where nobody browses it, so an
 * encode a batch never finished would sit there forever.
 */
class ConversionWorkspaceCleanerTest {

	private final WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
	private final ConversionFileNaming naming = new ConversionFileNaming(workspaceManager);
	private final ConversionWorkspaceCleaner cleaner = new ConversionWorkspaceCleaner(naming);

	@Test
	void clearsWhatAnInterruptedBatchLeftBehind(@TempDir Path workspace) throws Exception {
		when(workspaceManager.temp()).thenReturn(workspace.resolve("temp"));

		Path folder = Files.createDirectories(workspace.resolve("temp").resolve("conversion"));
		Path leftover = Files.writeString(folder.resolve("clip_H265.mp4"), "half an encode");

		cleaner.run(null);

		Assertions.assertThat(leftover).doesNotExist();
		Assertions.assertThat(folder).isEmptyDirectory();
	}

	/** The normal start: the folder is there from the last run and it is empty. */
	@Test
	void saysNothingWhenTheFolderIsAlreadyEmpty(@TempDir Path workspace) throws Exception {
		when(workspaceManager.temp()).thenReturn(workspace.resolve("temp"));

		Path folder = Files.createDirectories(workspace.resolve("temp").resolve("conversion"));

		cleaner.run(null);

		Assertions.assertThat(folder).isEmptyDirectory();
	}

	/** First start, or a workspace that was wiped: nothing to sweep, no failure. */
	@Test
	void startsCleanlyWhenTheFolderIsNotThereYet(@TempDir Path workspace) {
		when(workspaceManager.temp()).thenReturn(workspace.resolve("temp"));

		Assertions.assertThatCode(() -> cleaner.run(null)).doesNotThrowAnyException();
	}
}