package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Empties the conversion work folder when the application starts. Anything
 * still there is an encode a batch never finished - the process died, the
 * machine went down - and it is work, never the user's data: the original is
 * untouched until the encode is validated and moved into the library.
 *
 * <p>
 * Sweeping it at startup matters more now that the folder lives outside the
 * library: nobody browses it, so a leftover would sit there forever and the
 * disk would fill with encodes of files that were converted again later.
 */
@Slf4j
@Component
public class ConversionWorkspaceCleaner implements ApplicationRunner {

	private final ConversionFileNaming conversionFileNaming;

	public ConversionWorkspaceCleaner(ConversionFileNaming conversionFileNaming) {
		this.conversionFileNaming = conversionFileNaming;
	}

	@Override
	public void run(ApplicationArguments args) {
		Path folder = conversionFileNaming.workFolder();

		if (!Files.isDirectory(folder)) {
			return;
		}

		try (Stream<Path> entries = Files.list(folder)) {
			List<Path> leftovers = entries.filter(Files::isRegularFile).toList();

			leftovers.forEach(conversionFileNaming::discard);

			if (!leftovers.isEmpty()) {
				log.info("Cleared {} unfinished encode(s) left in {}", leftovers.size(), folder);
			}
		} catch (IOException e) {
			// Housekeeping must never stop the application from starting.
			log.warn("Could not sweep the conversion work folder {}", folder, e);
		}
	}
}