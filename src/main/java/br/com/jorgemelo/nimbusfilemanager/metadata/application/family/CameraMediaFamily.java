package br.com.jorgemelo.nimbusfilemanager.metadata.application.family;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.classifier.MediaSubcategoryRule;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Regular camera/phone output: {@code yyyyMMdd_HHmmss}, {@code yyyy-MM-dd ...},
 * the {@code IMG_/VID_/DSC_/PXL_} prefixes or one of the compact-camera
 * sequences in {@link #DEVICE}. Subcategory-only family - the capture date of
 * these names is handled by the generic/dashed date families.
 *
 * <p>
 * Classification matches by name only (mirrors the former rule); the folder
 * signature is exposed for the organization rule.
 */
@Component
public class CameraMediaFamily implements MediaSubcategoryRule {

	private static final String ORDER = "060_CAMERA";

	private static final Pattern DATETIME = Pattern.compile("^\\d{8}_\\d{6}.*");
	private static final Pattern DASH = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}.*");

	/**
	 * Compact-camera names whose prefix identifies the device and whose sequence
	 * number carries no date: DSC0#### (Sony Cyber-shot), DSCN#### (Nikon Coolpix),
	 * GEDC#### (General Imaging), IMGP#### (Pentax), CIMG#### (Casio), S#######
	 * (Samsung Digimax), P####### (Panasonic and Olympus) and the ###_#### that
	 * Kodak and Canon write inside DCIM. Each one was confirmed against the EXIF
	 * manufacturer of the files that carry it, not inferred from the name alone.
	 *
	 * <p>
	 * The digit count is exact and what follows it must not be a digit, so a longer
	 * numeric run - an id, a timestamp - is not mistaken for one of these
	 * sequences; the trailing group is what allows a copy suffix ({@code (1)}) and
	 * the extension.
	 *
	 * <p>
	 * One pattern per device instead of a single alternation: the combined
	 * expression crossed the regex-complexity limit, and separate patterns also
	 * read as the list of devices they are.
	 */
	private static final List<Pattern> DEVICE = List.of(Pattern.compile("^DSC\\d{5}(\\D.*)?"),
			Pattern.compile("^DSCN\\d{4}(\\D.*)?"), Pattern.compile("^GEDC\\d{4}(\\D.*)?"),
			Pattern.compile("^IMGP\\d{4}(\\D.*)?"), Pattern.compile("^CIMG\\d{4}(\\D.*)?"),
			Pattern.compile("^[SP]\\d{7}(\\D.*)?"), Pattern.compile("^\\d{3}_\\d{4}(\\D.*)?"));

	public static boolean matchesName(String fileName) {
		if (fileName == null) {
			return false;
		}

		String value = fileName.toUpperCase(Locale.ROOT);

		return DATETIME.matcher(value).matches() || DASH.matcher(value).matches() || isDeviceSequence(value)
				|| value.startsWith("IMG_") || value.startsWith("VID_") || value.startsWith("DSC_")
				|| value.startsWith("PXL_");
	}

	private static boolean isDeviceSequence(String value) {
		return DEVICE.stream().anyMatch(pattern -> pattern.matcher(value).matches());
	}

	public static boolean matchesPath(String path) {
		return PathUtils.containsAnyFolder(path, MediaSubcategory.CAMERA.folderName());
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