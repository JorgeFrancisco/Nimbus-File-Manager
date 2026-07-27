package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.constants.ConversionConstants;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionOptions;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureFileMove;
import br.com.jorgemelo.nimbusfilemanager.shared.util.ExtensionUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.FileNames;
import lombok.extern.slf4j.Slf4j;

/**
 * Puts the validated conversion into the library, next to the file it came
 * from, without ever overwriting anything. Both moves go through
 * {@link SecureFileMove} - the same SHA-256 baseline plus byte-for-byte verify
 * every other feature uses - because from the moment it leaves the workspace
 * the converted file is the user's media, not a regenerable artifact.
 */
@Slf4j
@Component
public class ConversionFilePlacement {

	private final SecureFileMove secureFileMove;
	private final ConversionFileNaming conversionFileNaming;

	public ConversionFilePlacement(SecureFileMove secureFileMove, ConversionFileNaming conversionFileNaming) {
		this.secureFileMove = secureFileMove;
		this.conversionFileNaming = conversionFileNaming;
	}

	/**
	 * Gives the finished file its real name, next to the source. That is the name
	 * the options ask for (the source name plus the affix); if something already
	 * occupies it - a previous conversion, or the source itself when no affix is
	 * set - the "(H.265)" suffix keeps the two apart instead of overwriting
	 * anything.
	 */
	public Path place(Path converted, Path source, ConversionOptions options) throws IOException {
		Path desired = conversionFileNaming.finalName(source, options);

		Path target = Files.exists(desired)
				? FileNames.nextAvailable(FileNames.withSuffix(desired, ConversionConstants.CONVERTED_SUFFIX))
				: desired;

		secureFileMove.move(converted, target, false);

		// The move says it verified the file byte for byte, so this can only fail if
		// something outside the application took the file away in between. It happened
		// once: the batch counted the video as converted while the catalog pointed at a
		// path with nothing behind it and the encode sat next to it under the temporary
		// name. One stat call turns that silent loss into a reported failure.
		if (!Files.exists(target)) {
			throw new IOException("The converted file is not at " + target + " after the move");
		}

		return target;
	}

	/**
	 * Gives the converted file the original's name once the original itself has
	 * left the folder (it went to quarantine), so the library keeps the name the
	 * user knows. Purely cosmetic: if the name is somehow taken or the rename
	 * fails, the file stays where it is and the conversion still counts as done.
	 */
	public Path renameToOriginalName(Path converted, Path source) {
		Path desired = FileNames.withExtension(source, ExtensionUtils.fromPath(converted));

		if (desired.equals(converted) || Files.exists(desired)) {
			return converted;
		}

		try {
			secureFileMove.move(converted, desired, false);

			return desired;
		} catch (Exception e) {
			log.warn("Could not rename the converted file {} to {}; keeping the suffixed name", converted, desired, e);

			return converted;
		}
	}
}