package br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure;

import java.io.IOException;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.ExternalToolNotRunnableException;

/**
 * A tool that is not there has to say so in its own words.
 *
 * <p>
 * Everything downstream depends on this distinction: when the failure arrives
 * as a generic one, the code that explains it reads the file's bytes instead,
 * and answers a question about the photo that nobody asked.
 */
class ExternalToolProcessTest {

	@Test
	void namesTheToolItCouldNotStart(@TempDir Path folder) {
		String missing = folder.resolve("ffmpeg-that-is-not-here").toString();

		ProcessBuilder builder = new ProcessBuilder(missing);

		Assertions.assertThatThrownBy(() -> ExternalToolProcess.start(builder, missing))
				.isInstanceOf(ExternalToolNotRunnableException.class).hasMessageContaining("ffmpeg-that-is-not-here")
				.hasCauseInstanceOf(IOException.class);
	}
}