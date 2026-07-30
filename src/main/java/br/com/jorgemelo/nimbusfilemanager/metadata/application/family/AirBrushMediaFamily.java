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
 * AirBrush editor output ({@code AirBrush_...}). Detected by name only (no
 * folder signature); owns subcategory and date extraction.
 */
@Component
public class AirBrushMediaFamily extends AbstractFileNameDateRule implements MediaSubcategoryRule {

	public AirBrushMediaFamily(Clock clock) {
		super(clock);
	}

	private static final String ORDER = "020_AIRBRUSH";

	public static boolean matchesName(String fileName) {
		if (fileName == null) {
			return false;
		}

		return fileName.toUpperCase(Locale.ROOT).startsWith("AIRBRUSH");
	}

	@Override
	public boolean supports(String fileName) {
		return matchesName(fileName);
	}

	@Override
	public LocalDateTime resolve(String fileName) {
		LocalDateTime date = parse(fileName, FileNameDatePatterns.DATE8_SEP_TIME6, "yyyyMMddHHmmss");

		if (date == null) {
			// No separator between day and time: the whole timestamp is one 14-digit run.
			date = parse(fileName, FileNameDatePatterns.DATE8_TIME6, "yyyyMMddHHmmss");
		}

		return date != null ? date : parse(fileName, FileNameDatePatterns.DATE8, "yyyyMMdd");
	}

	@Override
	public boolean supports(String fileName, String path) {
		return matchesName(fileName);
	}

	@Override
	public MediaSubcategory subcategory() {
		return MediaSubcategory.AIRBRUSH;
	}

	@Override
	public String name() {
		return ORDER;
	}
}