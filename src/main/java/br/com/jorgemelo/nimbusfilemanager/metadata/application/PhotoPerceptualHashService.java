package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataConstants;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.PhotoPerceptualFingerprint;
import br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.FfmpegPhotoHashProcessRunner;
import br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.FfmpegRunner;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ExternalToolGate;
import br.com.jorgemelo.nimbusfilemanager.processing.domain.enums.ExternalToolCategory;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolPaths;
import br.com.jorgemelo.nimbusfilemanager.shared.application.CoverageGenerated;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import br.com.jorgemelo.nimbusfilemanager.shared.util.FileValidationUtils;

/**
 * Computes a 256-bit DCT perceptual hash (pHash) for photos. FFmpeg decodes and
 * normalizes the first frame to a fixed 32x32 grayscale sample, which
 * {@link PerceptualHashCodec} turns into the packed hash - the same math the
 * video algorithm reuses per frame.
 *
 * <p>
 * The normalized luminance sample is returned with the hash and persisted. It
 * is later used for SSIM confirmation, so opening the Similar Photos screen
 * never decodes the original multi-megapixel files again.
 * </p>
 */
@Service
public class PhotoPerceptualHashService {

	private final ExternalToolPaths externalToolPaths;
	private final FfmpegRunner ffmpegRunner;
	private final WorkspaceManager workspaceManager;

	@Autowired
	@CoverageGenerated("Spring wiring: forwards to the constructor every test builds directly")
	public PhotoPerceptualHashService(ExternalToolPaths externalToolPaths, ExternalToolGate externalToolGate,
			FfmpegPhotoHashProcessRunner processRunner, WorkspaceManager workspaceManager) {
		this(externalToolPaths, (ffmpegPath, file) -> externalToolGate.run(ExternalToolCategory.FFMPEG_PHOTO_HASH,
				() -> processRunner.run(ffmpegPath, file)), workspaceManager);
	}

	PhotoPerceptualHashService(ExternalToolPaths externalToolPaths, FfmpegRunner ffmpegRunner,
			WorkspaceManager workspaceManager) {
		this.externalToolPaths = externalToolPaths;
		this.ffmpegRunner = ffmpegRunner;
		this.workspaceManager = workspaceManager;
	}

	public PhotoPerceptualFingerprint compute(Path file) {
		FileValidationUtils.validateFile(file);

		rejectZipContainerMasqueradingAsWebp(file);

		byte[] pixels;

		try {
			pixels = hashOf(file);
		} catch (Exception exception) {
			throw new IllegalStateException(
					"Could not run ffmpeg to compute perceptual hash for file: " + file + ". " + exception.getMessage(),
					exception);
		}

		if (pixels.length != MetadataConstants.SAMPLE_BYTES) {
			throw new IllegalStateException("Unexpected pixel data size computing perceptual hash for file: " + file
					+ ". Expected " + MetadataConstants.SAMPLE_BYTES + " bytes, got " + pixels.length);
		}

		return new PhotoPerceptualFingerprint(PerceptualHashCodec.hash256(pixels), pixels);
	}

	/**
	 * Decodes the file, or the part of it ffmpeg can read: the first frame of an
	 * animated sticker, or a photo without the trailer its camera appended. The
	 * slice is handed over as a file because the runner takes a path, and is
	 * deleted straight after - a byte-for-byte slice of the original, regenerated
	 * whenever it is needed again. It is written inside the application workspace,
	 * never in the system temp: that one is world-writable, and a file another user
	 * can swap between the write and the read is a file we should not hand to a
	 * decoder.
	 */
	private byte[] hashOf(Path file) throws Exception {
		byte[] decodable = decodableBytes(file);

		if (decodable.length == 0) {
			return ffmpegRunner.run(ffmpegPath(), file);
		}

		Path decodableFile = Files.createTempFile(workspaceManager.temp(), "decodable", ".tmp");

		try {
			Files.write(decodableFile, decodable);

			return ffmpegRunner.run(ffmpegPath(), decodableFile);
		} finally {
			Files.deleteIfExists(decodableFile);
		}
	}

	/**
	 * What ffmpeg can actually read, when the file itself defeats it: the first
	 * frame of an animated WebP, or a JPEG without the vendor trailer a camera left
	 * behind it. An empty result means the file decodes as it is, which is the
	 * ordinary case.
	 */
	private static byte[] decodableBytes(Path file) throws IOException {
		byte[] firstFrame = AnimatedWebp.firstFrame(file);

		return firstFrame.length > 0 ? firstFrame : JpegMainImage.withoutTrailer(file);
	}

	private void rejectZipContainerMasqueradingAsWebp(Path file) {
		String name = file.getFileName().toString().toLowerCase(Locale.ROOT);

		if (!name.endsWith(".webp")) {
			return;
		}

		try (InputStream input = Files.newInputStream(file)) {
			byte[] signature = input.readNBytes(4);

			if (signature.length == 4 && signature[0] == 'P' && signature[1] == 'K'
					&& ((signature[2] == 3 && signature[3] == 4) || (signature[2] == 5 && signature[3] == 6)
							|| (signature[2] == 7 && signature[3] == 8))) {
				throw new UnsupportedPhotoFingerprintException(
						"The .webp file is a ZIP/Lottie package, not a WebP image: " + file);
			}
		} catch (UnsupportedPhotoFingerprintException exception) {
			throw exception;
		} catch (IOException exception) {
			throw new IllegalStateException("Could not inspect WebP signature for file: " + file, exception);
		}
	}

	/** Hamming distance between two 256-bit pHashes. */
	public static int distance(byte[] first, byte[] second) {
		return PerceptualHashCodec.distance(first, second);
	}

	private String ffmpegPath() {
		return externalToolPaths.ffmpeg();
	}
}