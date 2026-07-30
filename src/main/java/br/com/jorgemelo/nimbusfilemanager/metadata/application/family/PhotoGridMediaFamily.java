package br.com.jorgemelo.nimbusfilemanager.metadata.application.family;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.classifier.MediaSubcategoryRule;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.FileNameDatePatterns;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.filename.rule.AbstractFileNameDateRule;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;

/**
 * PhotoGrid collage editor output ({@code PhotoGrid_<epochMillis>...}, e.g.
 * {@code PhotoGrid_1443567518248.jpg}). Detected by name only (no folder
 * signature); owns subcategory and date extraction. The numeric token is the
 * epoch-millis creation time, so the date is read from it when present, falling
 * back to the shared {@code yyyyMMdd[HHmmss]} patterns otherwise.
 */
@Component
public class PhotoGridMediaFamily extends AbstractFileNameDateRule implements MediaSubcategoryRule {

	public PhotoGridMediaFamily(Clock clock) {
		super(clock);
	}

	private static final String ORDER = "022_PHOTOGRID";

	/** Leading digit bounded to a real epoch, as in {@link FacebookMediaFamily}. */
	private static final Pattern EPOCH_MILLIS = Pattern.compile("PHOTOGRID[-_]([12]\\d{12})",
			Pattern.CASE_INSENSITIVE);

	public static boolean matchesName(String fileName) {
		if (fileName == null) {
			return false;
		}

		return fileName.toUpperCase(Locale.ROOT).startsWith("PHOTOGRID");
	}

	@Override
	public boolean supports(String fileName) {
		return matchesName(fileName);
	}

	@Override
	public LocalDateTime resolve(String fileName) {
		LocalDateTime epoch = fromEpochMillis(fileName, EPOCH_MILLIS);

		if (epoch != null) {
			return epoch;
		}

		LocalDateTime date = parse(fileName, FileNameDatePatterns.DATE8_SEP_TIME6, "yyyyMMddHHmmss");

		return date != null ? date : parse(fileName, FileNameDatePatterns.DATE8, "yyyyMMdd");
	}

	@Override
	public boolean supports(String fileName, String path) {
		return matchesName(fileName);
	}

	@Override
	public MediaSubcategory subcategory() {
		return MediaSubcategory.PHOTOGRID;
	}

	@Override
	public String name() {
		return ORDER;
	}
}