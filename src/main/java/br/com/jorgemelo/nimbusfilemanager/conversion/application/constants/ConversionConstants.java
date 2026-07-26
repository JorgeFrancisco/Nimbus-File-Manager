package br.com.jorgemelo.nimbusfilemanager.conversion.application.constants;

import java.util.Set;

/**
 * Contract data constants for the conversion domain: the preferences page and
 * keys that make the Conversão screen reopen with the options the user last
 * chose, the container every conversion targets, the name of the workspace
 * folder the encoder writes into, the suffix that keeps a converted file from
 * overwriting its source, and the codec names that already mean H.265.
 */
public final class ConversionConstants {

	public static final String PAGE_KEY = "conversion";
	public static final String QUALITY_KEY = "quality";
	public static final String AUDIO_KEY = "audio";
	public static final String DISPOSITION_KEY = "disposition";
	public static final String AFFIX_KEY = "nameAffix";
	public static final String AFFIX_POSITION_KEY = "affixPosition";

	/**
	 * Stored in place of an affix the user cleared. Preferences never keep a blank
	 * value, and the naming layer strips path separators from whatever is typed, so
	 * a stored separator can only ever mean "no affix".
	 */
	public static final String EMPTY_AFFIX_MARKER = "/";

	/**
	 * Every conversion produces an MP4. It is the one container that plays
	 * everywhere - phones, TVs, browsers, players - which is the whole point of
	 * standardising the library on it, and it holds H.265 natively.
	 */
	public static final String OUTPUT_EXTENSION = "mp4";

	/**
	 * What the converted file is marked with out of the box, so a conversion never
	 * silently produces a file the user cannot tell apart from its source.
	 */
	public static final String DEFAULT_NAME_AFFIX = "_H265";

	/**
	 * Marks the file ffmpeg is still writing. Together with
	 * {@link #TEMPORARY_EXTENSION} it keeps the encode recognisable in the source
	 * folder and invisible to the inventory.
	 */
	public static final String TEMPORARY_SUFFIX = "_temp";

	/**
	 * Extension of the file being encoded. It is one of the extensions the
	 * inventory skips by default, which is what makes it safe to encode inside the
	 * watched library instead of copying the result in from elsewhere.
	 */
	public static final String TEMPORARY_EXTENSION = "tmp";

	/**
	 * Added to the converted file's name when the name it should take is already
	 * occupied (typically by the source itself, when no affix was configured), so
	 * the two never collide.
	 */
	public static final String CONVERTED_SUFFIX = " (H.265)";

	/**
	 * What ffprobe reports for a video that is already H.265. Lowercase, matched
	 * against a trimmed codec name.
	 */
	public static final Set<String> HEVC_CODECS = Set.of("hevc", "h265", "h.265", "x265");

	private ConversionConstants() {
	}
}