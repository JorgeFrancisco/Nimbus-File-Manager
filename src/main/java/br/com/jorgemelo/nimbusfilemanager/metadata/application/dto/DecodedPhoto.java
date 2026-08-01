package br.com.jorgemelo.nimbusfilemanager.metadata.application.dto;

import java.awt.image.BufferedImage;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.image.PhotoDecoder;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.DecoderType;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.OrientationSource;

/**
 * Ephemeral result of one {@link PhotoDecoder} decode - valid only during the
 * calling operation, never cached and never shared between calls (the decoder
 * holds no pixels between invocations). The {@link #image} is already oriented
 * (EXIF/DB precedence resolved) and flattened onto white when the source had
 * alpha, so callers consume it directly.
 *
 * @param image the decoded, oriented, alpha-flattened frame 0
 * @param format the ImageIO format name that decoded it (e.g. "JPEG", "png",
 * "webp")
 * @param decoder which reader family produced it (native vs WEBP plugin)
 * @param orientationSource where the applied orientation came from
 * @param alphaFlattened whether the source had an alpha channel that was
 * composited over white
 * @param frameIndex the decoded frame index; always 0 (frame 0 for animated
 * GIF/WEBP)
 */
public record DecodedPhoto(BufferedImage image, String format, DecoderType decoder, OrientationSource orientationSource,
		boolean alphaFlattened, int frameIndex) {
}