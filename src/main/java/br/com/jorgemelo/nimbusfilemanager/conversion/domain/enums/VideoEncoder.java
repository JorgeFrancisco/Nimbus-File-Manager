package br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums;

import java.util.List;

/**
 * How the H.265 stream is produced. Software encoding is the default because it
 * is what preserves quality per byte; a hardware encoder hands the work to the
 * GPU block, which is several times faster and barely touches the processor, at
 * the cost of a larger file for the same visual result.
 *
 * <p>
 * The quality knob has a different name and scale on every encoder, so each one
 * carries its own arguments here and nothing else in the application has to
 * know the difference. The hardware entries are declared in the order they are
 * tried: which one a machine can actually use is decided at runtime by probing,
 * never assumed from the card's name or from the encoders ffmpeg lists.
 */
public enum VideoEncoder {

	/** libx265: processor only, the best size for a given quality. */
	SOFTWARE("libx265"),

	/** NVIDIA. Absent from whole lines (the MX series has no encoder block). */
	NVENC("hevc_nvenc"),

	/** Intel Quick Sync, in the integrated GPU most machines have. */
	QUICK_SYNC("hevc_qsv"),

	/** AMD, through the AMF runtime that ships with its drivers. */
	AMF("hevc_amf");

	private final String ffmpegName;

	VideoEncoder(String ffmpegName) {
		this.ffmpegName = ffmpegName;
	}

	public String ffmpegName() {
		return ffmpegName;
	}

	public boolean hardware() {
		return this != SOFTWARE;
	}

	/**
	 * The arguments that ask this encoder for a given quality level, on its own
	 * scale. Software takes a CRF plus a preset; the hardware ones take their
	 * respective constant-quality knobs, which mean roughly the same thing to the
	 * user and nothing alike to ffmpeg.
	 */
	public List<String> qualityArguments(int quality, String preset) {
		return switch (this) {
		case SOFTWARE -> List.of("-crf", String.valueOf(quality), "-preset", preset);
		// Lookahead lets each encoder spend bits where they matter, which is the
		// closest any of them gets to CRF behaviour.
		case NVENC -> List.of("-rc", "vbr", "-cq", String.valueOf(quality), "-rc-lookahead", "20");
		case QUICK_SYNC -> List.of("-global_quality", String.valueOf(quality), "-look_ahead", "1");
		case AMF -> List.of("-rc", "cqp", "-qp_i", String.valueOf(quality), "-qp_p", String.valueOf(quality));
		};
	}

	/** The hardware encoders, in the order a machine should be probed for them. */
	public static List<VideoEncoder> hardwareCandidates() {
		return List.of(NVENC, QUICK_SYNC, AMF);
	}
}