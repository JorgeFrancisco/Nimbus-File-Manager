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
 * Image saved from the Facebook app ({@code FB_IMG_<epochMillis>}). The numeric
 * token is when the app wrote the file, which is the only date these downloads
 * carry - they have no EXIF - so recovering it beats falling back to the
 * modification time. Classified as OTHER rather than CAMERA: the picture was
 * downloaded, not taken by this device.
 */
@Component
public class FacebookMediaFamily extends AbstractFileNameDateRule implements MediaSubcategoryRule {

	private static final String ORDER = "024_FACEBOOK";

	/**
	 * The leading digit bounds the token to a real epoch: a 13-digit run starting
	 * with a zero is the "no timestamp" sentinel and would otherwise be recorded as
	 * a 1970 capture date, which the plausible-year guard alone accepts.
	 */
	private static final Pattern EPOCH_MILLIS = Pattern.compile("FB_IMG_([12]\\d{12})", Pattern.CASE_INSENSITIVE);

	public FacebookMediaFamily(Clock clock) {
		super(clock);
	}

	public static boolean matchesName(String fileName) {
		if (fileName == null) {
			return false;
		}

		return fileName.toUpperCase(Locale.ROOT).startsWith("FB_IMG");
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