package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
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
 *
 * <p>
 * It sweeps where the encoding happens, and that is now the worker. It used to
 * be the application, and had to be kept out of the worker for the mirror image
 * of today's reason: a worker starting up would have emptied the folder an
 * application was encoding into.
 *
 * <p>
 * A start is still the one moment at which nothing can be in use here, and that
 * is what makes a blanket sweep safe rather than a guess. Runners run before the
 * ready event that starts the worker loop, so no execution has been claimed yet
 * and no encode of this process can exist - and the other process no longer
 * encodes at all.
 */
@Slf4j
@Component
@Profile(NimbusProfiles.WORKER)
public class ConversionWorkspaceCleaner implements ApplicationRunner {

	private final ConversionFileNaming conversionFileNaming;

	public ConversionWorkspaceCleaner(ConversionFileNaming conversionFileNaming) {
		this.conversionFileNaming = conversionFileNaming;
	}

	@Override
	public void run(ApplicationArguments args) {
		Path folder = conversionFileNaming.workFolder();

		try (Stream<Path> entries = Files.list(folder)) {
			List<Path> leftovers = entries.filter(Files::isRegularFile).toList();

			leftovers.forEach(conversionFileNaming::discard);

			if (!leftovers.isEmpty()) {
				log.info("Cleared {} unfinished encode(s) left in {}", leftovers.size(), folder);
			}
		} catch (NoSuchFileException _) {
			// The folder appears with the first conversion; before that there is nothing
			// to sweep and nothing worth saying.
		} catch (IOException e) {
			// Anything else - a file where the folder should be, a drive that will not
			// list it - is odd enough to report, and never a reason to stop the
			// application from starting.
			log.warn("Could not sweep the conversion work folder {}", folder, e);
		}
	}
}