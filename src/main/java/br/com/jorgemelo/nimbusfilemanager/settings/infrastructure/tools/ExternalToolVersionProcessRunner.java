package br.com.jorgemelo.nimbusfilemanager.settings.infrastructure.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolProbe;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs {@code <tool> -version} to find out whether the tool really works from
 * the configured path. Spawning it is the only answer that covers every way it
 * can be wrong at once: file missing, missing DLLs, wrong architecture, or a
 * bare command that PATH does not resolve.
 */
@Slf4j
@Component
public class ExternalToolVersionProcessRunner implements ExternalToolProbe {

	private static final long TIMEOUT_SECONDS = 10;

	@Override
	public Optional<String> version(String executable) {
		if (executable == null || executable.isBlank()) {
			return Optional.empty();
		}

		Process process = null;

		try {
			process = new ProcessBuilder(executable, "-version").redirectErrorStream(true).start();

			String firstLine = readFirstLine(process);

			if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) {
				return Optional.empty();
			}

			return Optional.ofNullable(firstLine).filter(line -> !line.isBlank());
		} catch (InterruptedException _) {
			Thread.currentThread().interrupt();

			return Optional.empty();
		} catch (IOException e) {
			// An absent or unusable tool is the normal state before installing, so it
			// is reported by the return value and never as an error in the log.
			log.debug("Tool did not run: {}", executable, e);

			return Optional.empty();
		} finally {
			if (process != null) {
				process.destroy();
			}
		}
	}

	private String readFirstLine(Process process) throws IOException {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			return reader.readLine();
		}
	}
}