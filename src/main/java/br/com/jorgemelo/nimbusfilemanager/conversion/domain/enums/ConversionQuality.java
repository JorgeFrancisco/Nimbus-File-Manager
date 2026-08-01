package br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums;

/**
 * The quality profiles offered on the Conversão screen. The encoder knobs (CRF,
 * preset and which encoder to use) live here and are never shown to or chosen
 * by the user: the screen offers "alta qualidade", "equilibrado" and "rápido",
 * and this enum is the single place that says what those mean in encoder terms.
 */
public enum ConversionQuality {

	/** Visually transparent for most sources; noticeably larger files. */
	HIGH_QUALITY(false, 18),

	/** The recommended default: a large size reduction at a very small cost. */
	BALANCED(false, 22),

	/**
	 * {@link #HIGH_QUALITY} on the GPU: several times faster and it leaves the
	 * processor free, at the cost of a larger file for the same visual result.
	 */
	FAST_HIGH_QUALITY(true, 18),

	/** {@link #BALANCED} on the GPU, and the recommended way to use one. */
	FAST_BALANCED(true, 22);

	/**
	 * The x265 preset every profile encodes with: the balance point between
	 * encoding time and compression. A slower preset buys a few percent of size for
	 * several times the time, which a whole-library batch cannot afford. The
	 * hardware encoders ignore it - their speed is fixed in silicon.
	 */
	public static final String PRESET = "medium";

	private final boolean hardware;

	/**
	 * Quality level on the scale of the encoder that runs it. The hardware numbers
	 * are not the software ones: measured on real footage, a GPU encoder needs a
	 * lower number - and spends more bits - to hold the quality its software
	 * counterpart reaches, because it compresses less efficiently. These two were
	 * calibrated against the software profiles rather than copied from them.
	 */
	private final int quality;

	ConversionQuality(boolean hardware, int quality) {
		this.hardware = hardware;
		this.quality = quality;
	}

	/**
	 * The quality level on the scale of whichever encoder runs it - a CRF for
	 * software, a constant-quality knob for the hardware ones.
	 */
	public int quality() {
		return quality;
	}

	/**
	 * Whether the profile asks for a GPU encoder. Which one that is depends on the
	 * machine and is discovered at runtime, so no profile ever names a vendor.
	 */
	public boolean requiresHardware() {
		return hardware;
	}

	/**
	 * The software profile of the same quality level, which is what a hardware
	 * attempt falls back to when the GPU refuses a file. Falling back to a
	 * different quality than the user picked would silently change the result.
	 */
	public ConversionQuality softwareEquivalent() {
		return switch (this) {
		case FAST_HIGH_QUALITY -> HIGH_QUALITY;
		case FAST_BALANCED -> BALANCED;
		default -> this;
		};
	}
}