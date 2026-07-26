package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.constants.ConversionConstants;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionOptions;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.NameAffixPosition;
import br.com.jorgemelo.nimbusfilemanager.shared.util.FileNames;
import lombok.extern.slf4j.Slf4j;

/**
 * Names both files a conversion produces: the one ffmpeg writes while it works
 * and the one that stays in the library.
 *
 * <p>
 * The encode happens <b>in the source folder</b>, which makes the final step a
 * rename instead of a copy - moving a finished 4 GB file from another volume
 * would take as long as part of the encode itself. What makes that safe is the
 * {@code .tmp} extension: it is one of the extensions the inventory skips by
 * default ({@code ScanExclusionService}), so a half-written file is never
 * cataloged, fingerprinted or shown, and the {@code _temp} marker keeps it
 * recognisable if a crash ever leaves one behind.
 *
 * <p>
 * The converted file only takes its real name once it is validated, so a file
 * with the final name is always a finished one.
 */
@Slf4j
@Component
public class ConversionFileNaming {

	/**
	 * Characters no Windows/POSIX file name can hold, plus the separators - the
	 * affix is free text typed by the user and lands directly in a file name.
	 */
	private static final String ILLEGAL_AFFIX_CHARACTERS = "[\\\\/:*?\"<>|\\p{Cntrl}]";

	/** Long enough for "- H.265 1080p", short enough to keep paths sane. */
	private static final int MAX_AFFIX_LENGTH = 40;

	/**
	 * What the user typed, minus everything a file name cannot hold. Empty when
	 * nothing usable is left, which means the converted file keeps the source name.
	 *
	 * <p>
	 * Spaces are kept on purpose - a prefix like {@code "HEVC - "} needs its
	 * trailing one, and the extension always follows the affix, so a name can never
	 * end in whitespace anyway.
	 */
	public String affix(ConversionOptions options) {
		String raw = options.nameAffix();

		if (raw == null || raw.isBlank()) {
			return "";
		}

		String cleaned = raw.replaceAll(ILLEGAL_AFFIX_CHARACTERS, "");

		if (cleaned.isBlank()) {
			return "";
		}

		return cleaned.length() > MAX_AFFIX_LENGTH ? cleaned.substring(0, MAX_AFFIX_LENGTH) : cleaned;
	}

	/**
	 * The name the converted file is meant to end up with: the source name with the
	 * user's affix and the MP4 extension. It may already be taken (by the source
	 * itself when no affix is set), which is what the placement step resolves.
	 */
	public Path finalName(Path source, ConversionOptions options) {
		String affix = affix(options);

		Path named = FileNames.withExtension(source, ConversionConstants.OUTPUT_EXTENSION);

		if (affix.isEmpty()) {
			return named;
		}

		return options.affixPosition() == NameAffixPosition.PREFIX ? FileNames.withPrefix(named, affix)
				: FileNames.withSuffix(named, affix);
	}

	/**
	 * A free path in the source folder for ffmpeg to write into: the final name
	 * plus the {@code _temp} marker and the {@code .tmp} extension the inventory
	 * ignores.
	 */
	public Path temporaryFor(Path source, ConversionOptions options) {
		Path marked = FileNames.withSuffix(finalName(source, options), ConversionConstants.TEMPORARY_SUFFIX);

		return FileNames.nextAvailable(FileNames.withExtension(marked, ConversionConstants.TEMPORARY_EXTENSION));
	}

	/**
	 * Best-effort cleanup of a conversion that will not be kept (it failed, was
	 * cancelled, or was already renamed into place). A stray temporary file is not
	 * worth failing a conversion over, so problems are logged and swallowed.
	 */
	public void discard(Path temporary) {
		if (temporary == null) {
			return;
		}

		try {
			Files.deleteIfExists(temporary);
		} catch (IOException e) {
			log.debug("Could not delete the temporary conversion file {}", temporary, e);
		}
	}
}