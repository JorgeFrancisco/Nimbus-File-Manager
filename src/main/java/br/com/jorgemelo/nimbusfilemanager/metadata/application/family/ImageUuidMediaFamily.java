package br.com.jorgemelo.nimbusfilemanager.metadata.application.family;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.classifier.MediaSubcategoryRule;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.FileNameDatePatterns;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.filename.rule.AbstractFileNameDateRule;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;

/**
 * Files named {@code IMAGE_<uuid>} - treated as regular camera output. Ordered
 * late (080), so it only claims names no more specific family matched. Detected
 * by name only; owns its date extraction.
 */
@Component
public class ImageUuidMediaFamily extends AbstractFileNameDateRule implements MediaSubcategoryRule {

	public ImageUuidMediaFamily(Clock clock) {
		super(clock);
	}

	private static final String ORDER = "080_IMAGE_UUID";

	public static boolean matchesName(String fileName) {
		if (fileName == null) {
			return false;
		}

		return fileName.toUpperCase(Locale.ROOT).startsWith("IMAGE_");
	}

	@Override
	public boolean supports(String fileName) {
		return matchesName(fileName);
	}

	@Override
	public LocalDateTime resolve(String fileName) {
		return parse(fileName, FileNameDatePatterns.DATE8_UNDERSCORE_TIME6, "yyyyMMddHHmmss");
	}

	@Override
	public boolean supports(String fileName, String path) {
		return matchesName(fileName);
	}

	@Override
	public MediaSubcategory subcategory() {
		return MediaSubcategory.CAMERA;
	}

	@Override
	public String name() {
		return ORDER;
	}
}