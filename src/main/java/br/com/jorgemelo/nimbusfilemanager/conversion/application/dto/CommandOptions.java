package br.com.jorgemelo.nimbusfilemanager.conversion.application.dto;

import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionQuality;

/**
 * What one ffmpeg attempt should do, as decided by the transcoder. Everything
 * here can change between attempts of the same file: the audio is re-encoded on
 * the second try when the container rejected the original, and subtitles or
 * data tracks are dropped on a further try when MP4 cannot hold them.
 *
 * @param copyVideo when the source is already H.265, so the video stream is
 * remuxed instead of re-encoded and {@code quality} does not apply
 */
public record CommandOptions(ConversionQuality quality, boolean copyVideo, boolean encodeAudioAsAac,
		boolean includeSubtitles, boolean includeData) {
}