package br.com.jorgemelo.nimbusfilemanager.metadata.application.family;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.classifier.MediaSubcategoryRule;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.filename.rule.AbstractFileNameDateRule;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;

/**
 * FaceApp editor output ({@code FaceApp_<epochMillis>}), the same shape the
 * other editors use. The export strips the original EXIF, so the epoch token is
 * the only date left in the file. Classified as OTHER, like the remaining
 * editors that are not worth a folder of their own.
 */
@Component
public class FaceAppMediaFamily extends AbstractFileNameDateRule implements MediaSubcategoryRule {

	private static final String ORDER = "026_FACEAPP";

	/** Leading digit bounded to a real epoch, as in {@link FacebookMediaFamily}. */
	private static final Pattern EPOCH_MILLIS = Pattern.compile("FACEAPP[-_]([12]\\d{12})", Pattern.CASE_INSENSITIVE);

	public FaceAppMediaFamily(Clock clock) {
		super(clock);
	}

	public static boolean matchesName(String fileName) {
		if (fileName == null) {
			return false;
		}

		return fileName.toUpperCase(Locale.ROOT).startsWith("FACEAPP");
	}

	@Override
	public boolean supports(String fileName) {
		return matchesName(fileName);
	}

	@Override
	public LocalDateTime resolve(String fileName) {
		return fromEpochMillis(fileName, EPOCH_MILLIS);
	}

	@Override
	public boolean supports(String fileName, String path) {
		return matchesName(fileName);
	}

	@Override
	public MediaSubcategory subcategory() {
		return MediaSubcategory.OTHER;
	}

	@Override
	public String name() {
		return ORDER;
	}
}