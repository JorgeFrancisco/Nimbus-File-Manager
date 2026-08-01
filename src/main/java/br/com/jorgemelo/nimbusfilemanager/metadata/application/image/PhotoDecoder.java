package br.com.jorgemelo.nimbusfilemanager.metadata.application.image;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.stereotype.Component;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifIFD0Directory;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.DecodedPhoto;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.DecoderType;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.OrientationSource;

/**
 * Stateless, shared photo decoder used by the thumbnail pipeline
 * ({@code PhotoThumbnailService}). It holds no pixels between calls - only the
 * abstraction/code is shared, never the images.
 *
 * <p>
 * It goes through {@link ImageReader}/{@link ImageReadParam} rather than plain
 * {@code ImageIO.read} so it controls the frame, the subsampling factor, the
 * reader selection and resource disposal, and can distinguish an
 * <b>unsupported</b> format (no reader at all -
 * {@link UnsupportedDecodeException}) from a <b>corrupt</b> file of a supported
 * format (an {@link IOException}). WEBP support is contributed purely through
 * the ImageIO SPI (TwelveMonkeys); no TwelveMonkeys class is referenced here.
 *
 * <p>
 * Large images are read subsampled toward {@link #THUMBNAIL_SUBSAMPLE_MAX_SIDE}
 * so a huge original is never fully materialized just to build a small
 * thumbnail.
 *
 * <p>
 * Every decode: reads frame 0 (frame 0 for animated GIF/WEBP); resolves
 * orientation with a single, non-cumulative precedence (file EXIF wins over the
 * persisted DB rotation); and flattens any alpha over white. The returned
 * {@link DecodedPhoto} is ephemeral.
 */
@Component
public class PhotoDecoder {

	/**
	 * Longest side a decode subsamples down toward - a pure memory guard so a large
	 * original is not fully materialized just to produce a thumbnail.
	 */
	static final int THUMBNAIL_SUBSAMPLE_MAX_SIDE = 1280;

	/**
	 * Decodes {@code file} into an oriented, alpha-flattened frame-0 image.
	 *
	 * @param file the photo to read
	 * @param dbRotation persisted {@code media_metadata.rotation} in degrees, used
	 * only as a fallback when the file has no EXIF orientation; may be null
	 * @throws UnsupportedDecodeException no registered reader can decode this
	 * format
	 * @throws IOException missing, unreadable or corrupt file
	 */
	public DecodedPhoto decode(Path file, Integer dbRotation) throws UnsupportedDecodeException, IOException {
		try (ImageInputStream iis = ImageIO.createImageInputStream(file.toFile())) {
			if (iis == null) {
				throw new IOException("Could not open image stream: " + file);
			}

			Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);

			if (!readers.hasNext()) {
				throw new UnsupportedDecodeException("No ImageIO reader for file: " + file);
			}

			ImageReader reader = readers.next();

			reader.setInput(iis, true, true);

			BufferedImage raw;
			String format;

			try {
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);

				ImageReadParam param = reader.getDefaultReadParam();

				int subsampling = thumbnailSubsampling(width, height);

				if (subsampling > 1) {
					param.setSourceSubsampling(subsampling, subsampling, 0, 0);
				}

				raw = reader.read(0, param);
				format = reader.getFormatName();
			} catch (IOException | ArrayIndexOutOfBoundsException | IllegalArgumentException
					| NegativeArraySizeException e) {
				// A reader existed, so the format is supported: any failure now means the
				// bytes are corrupt/truncated, which is a real IOException, not UNSUPPORTED.
				throw new IOException("Corrupt image: " + file, e);
			} finally {
				reader.dispose();
			}

			if (raw == null) {
				throw new IOException("Reader returned no image for file: " + file);
			}

			DecoderType decoderType = isWebp(format) ? DecoderType.WEBP_PLUGIN : DecoderType.IMAGEIO_NATIVE;

			int exifOrientation = readExifOrientation(file);

			BufferedImage oriented;

			OrientationSource orientationSource;

			if (exifOrientation >= 1 && exifOrientation <= 8) {
				oriented = applyExifOrientation(raw, exifOrientation);
				orientationSource = OrientationSource.EXIF;
			} else if (dbRotation != null && Math.floorMod(dbRotation, 360) != 0) {
				oriented = applyDbRotation(raw, Math.floorMod(dbRotation, 360));
				orientationSource = OrientationSource.DB_ROTATION;
			} else {
				oriented = raw;
				orientationSource = OrientationSource.NONE;
			}

			boolean hadAlpha = oriented.getColorModel().hasAlpha();

			BufferedImage rgb = flattenOnWhite(oriented);

			return new DecodedPhoto(rgb, format, decoderType, orientationSource, hadAlpha, 0);
		}
	}

	/**
	 * Memory guard: subsample large images toward
	 * {@link #THUMBNAIL_SUBSAMPLE_MAX_SIDE}.
	 */
	int thumbnailSubsampling(int width, int height) {
		return Math.max(1, Math.max(width, height) / THUMBNAIL_SUBSAMPLE_MAX_SIDE);
	}

	/**
	 * EXIF Orientation tag (1-8) read via metadata-extractor, or {@code 0} when
	 * absent or unreadable - callers treat 0 as "no EXIF orientation".
	 */
	int readExifOrientation(Path file) {
		try {
			Metadata metadata = ImageMetadataReader.readMetadata(file.toFile());

			ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);

			if (ifd0 == null) {
				return 0;
			}

			Integer orientation = ifd0.getInteger(ExifDirectoryBase.TAG_ORIENTATION);

			return orientation == null ? 0 : orientation;
		} catch (Exception _) {
			return 0;
		}
	}

	/**
	 * Applies one of the eight EXIF orientations (rotations and mirror flips). Uses
	 * nearest-neighbour so re-orienting never resamples pixel values, keeping the
	 * hash defined purely by rotation/mirroring.
	 */
	BufferedImage applyExifOrientation(BufferedImage image, int orientation) {
		if (orientation == 1) {
			return image;
		}

		int width = image.getWidth();
		int height = image.getHeight();
		boolean swapDimensions = orientation >= 5;

		AffineTransform transform = switch (orientation) {
		case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
		case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
		case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
		case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
		case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
		case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
		case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
		default -> new AffineTransform();
		};

		int targetWidth = swapDimensions ? height : width;
		int targetHeight = swapDimensions ? width : height;
		int type = image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;

		BufferedImage result = new BufferedImage(targetWidth, targetHeight, type);

		Graphics2D graphics = result.createGraphics();

		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
					RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			graphics.drawImage(image, transform, null);
		} finally {
			graphics.dispose();
		}

		return result;
	}

	/**
	 * Fallback orientation from the persisted DB rotation (degrees), expressed
	 * through the equivalent EXIF rotation so both paths share one implementation.
	 */
	BufferedImage applyDbRotation(BufferedImage image, int degrees) {
		return switch (degrees) {
		case 90 -> applyExifOrientation(image, 6);
		case 180 -> applyExifOrientation(image, 3);
		case 270 -> applyExifOrientation(image, 8);
		default -> image;
		};
	}

	/**
	 * Composites the image onto a solid white background as {@code TYPE_INT_RGB} so
	 * a transparent source (e.g. an alpha PNG/WEBP) renders on white in the
	 * thumbnail. Opaque images are copied unchanged.
	 */
	BufferedImage flattenOnWhite(BufferedImage image) {
		BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);

		Graphics2D graphics = rgb.createGraphics();

		try {
			graphics.setColor(Color.WHITE);
			graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
			graphics.drawImage(image, 0, 0, null);
		} finally {
			graphics.dispose();
		}

		return rgb;
	}

	boolean isWebp(String formatName) {
		return formatName != null && formatName.toLowerCase(Locale.ROOT).contains("webp");
	}
}