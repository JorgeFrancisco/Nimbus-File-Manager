package br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure;

import java.io.IOException;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.ExternalToolNotRunnableException;

/**
 * Starts an external tool, telling "it could not be launched" apart from
 * anything it might say once it runs.
 *
 * <p>
 * {@link ProcessBuilder#start()} reports both a missing binary and an
 * unreadable one as a plain {@code IOException}, which upstream then wraps
 * together with every decoding error into one message. Naming this case here,
 * once, is what lets a caller answer whether the file or the installation is at
 * fault - and the runners that spawn ffmpeg would otherwise each repeat the
 * same three lines.
 */
final class ExternalToolProcess {

	private ExternalToolProcess() {
	}

	static Process start(ProcessBuilder builder, String executable) {
		try {
			return builder.start();
		} catch (IOException exception) {
			throw new ExternalToolNotRunnableException(executable, exception);
		}
	}
}