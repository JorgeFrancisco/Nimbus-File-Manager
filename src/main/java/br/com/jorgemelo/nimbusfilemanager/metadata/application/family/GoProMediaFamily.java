package br.com.jorgemelo.nimbusfilemanager.metadata.application.family;

import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.classifier.MediaSubcategoryRule;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * GoPro output ({@code GOPR/GH/GX} prefixes or the {@link #BURST} sequence) or
 * a GoPro folder. Subcategory-only family; capture date falls to the
 * generic/dashed date families.
 */
@Component
public class GoProMediaFamily implements MediaSubcategoryRule {

	private static final String ORDER = "050_GOPRO";

	/**
	 * The sequence a GoPro writes for burst and time-lapse frames, alongside the
	 * GOPR of single shots: confirmed against the EXIF manufacturer of the files
	 * that carry it. Anchored with an exact digit count so an unrelated name
	 * starting with G0 is not swept in.
	 */
	private static final Pattern BURST = Pattern.compile("^G0\\d{6}(\\D.*)?");

	public static boolean matchesName(String fileName) {
		if (fileName == null) {
			return false;
		}

		String value = fileName.toUpperCase(Locale.ROOT);

		return value.startsWith("GOPR") || value.startsWith("GH") || value.startsWith("GX")
				|| BURST.matcher(value).matches();
	}

	public static boolean matchesPath(String path) {
		return PathUtils.containsAnyFolder(path, MediaSubcategory.GOPRO.folderName());
	}

	@Override
	public boolean supports(String fileName, String path) {
		return matchesName(fileName) || matchesPath(path);
	}

	@Override
	public MediaSubcategory subcategory() {
		return MediaSubcategory.GOPRO;
	}

	@Override
	public String name() {
		return ORDER;
	}
}