package br.com.jorgemelo.nimbusfilemanager.metadata.application.family;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.classifier.MediaSubcategoryRule;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.FileNameDatePatterns;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.filename.rule.AbstractFileNameDateRule;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Screenshots and screen recordings (incl. the pt-BR "Captura" naming). Single
 * home for detection (name + folder), subcategory and date extraction; consumed
 * by both the classifier and the filename date engine.
 */
@Component
public class ScreenshotMediaFamily extends AbstractFileNameDateRule implements MediaSubcategoryRule {

	public ScreenshotMediaFamily(Clock clock) {
		super(clock);
	}

	private static final String ORDER = "030_SCREENSHOT";

	public static boolean matchesName(String fileName) {
		if (fileName == null) {
			return false;
		}

		String value = fileName.toUpperCase(Locale.ROOT);

		return value.startsWith("SCREENSHOT") || value.startsWith("SCREENRECORD") || value.startsWith("CAPTURA");
	}

	public static boolean matchesPath(String path) {
		return PathUtils.containsAnyFolder(path, MediaSubcategory.SCREENSHOT.folderName(), "CAPTURA");
	}

	@Override
	public boolean supports(String fileName) {
		return matchesName(fileName);
	}

	@Override
	public LocalDateTime resolve(String fileName) {
		LocalDateTime date = parse(fileName, FileNameDatePatterns.DATE8_SEP_TIME6, "yyyyMMddHHmmss");

		return date != null ? date : parse(fileName, FileNameDatePatterns.DATE8, "yyyyMMdd");
	}

	@Override
	public boolean supports(String fileName, String path) {
		return matchesName(fileName) || matchesPath(path);
	}

	@Override
	public MediaSubcategory subcategory() {
		return MediaSubcategory.SCREENSHOT;
	}

	@Override
	public String name() {
		return ORDER;
	}
}