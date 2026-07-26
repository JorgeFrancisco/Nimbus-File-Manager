package br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums;

/**
 * The two quality profiles offered on the Conversão screen. The encoder knobs
 * (CRF and preset) live here and are never shown to or chosen by the user: the
 * screen offers "alta qualidade" and "equilibrado", and this enum is the single
 * place that says what those mean in encoder terms. A future hardware encoder
 * (NVENC, Quick Sync, AMF) or codec (AV1) maps its own knobs from these same
 * profiles, so the user-facing vocabulary never changes.
 */
public enum ConversionQuality {

	/** Visually transparent for most sources; noticeably larger files. */
	HIGH_QUALITY(18, "medium"),

	/** The recommended default: a large size reduction at a very small cost. */
	BALANCED(22, "medium");

	private final int crf;
	private final String preset;

	ConversionQuality(int crf, String preset) {
		this.crf = crf;
		this.preset = preset;
	}

	public int crf() {
		return crf;
	}

	public String preset() {
		return preset;
	}
}