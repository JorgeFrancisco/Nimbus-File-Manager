package br.com.jorgemelo.nimbusfilemanager.conversion.application.dto;

/**
 * What a conversion had to give up for MP4 to accept the file. The three travel
 * together everywhere - they are decided in the same place, reported in the
 * same line of the report and mean nothing apart - so they travel as one thing
 * instead of as three more booleans on a record that already carried a dozen
 * fields.
 *
 * @param audioFallback the original audio stream was re-encoded to AAC.
 * @param subtitlesDropped an image-based subtitle track was left behind.
 * @param dataDropped the camera's telemetry or timecode track was left behind.
 */
public record ConversionAdjustments(boolean audioFallback, boolean subtitlesDropped, boolean dataDropped) {

	public static ConversionAdjustments none() {
		return new ConversionAdjustments(false, false, false);
	}
}