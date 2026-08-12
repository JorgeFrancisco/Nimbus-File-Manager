package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataConstants;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.PhotoPerceptualFingerprint;
import br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.FfmpegGroupRunner;
import br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.FfmpegPhotoHashProcessRunner;
import br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.FfmpegRunner;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ExternalToolGate;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolPaths;
import br.com.jorgemelo.nimbusfilemanager.shared.application.CoverageGenerated;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExternalToolCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ProcessingMetrics;

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
	private final FfmpegGroupRunner ffmpegGroupRunner;
	private final WorkspaceManager workspaceManager;

	@Autowired
	@CoverageGenerated("Spring wiring: forwards to the constructor every test builds directly")
	public PhotoPerceptualHashService(ExternalToolPaths externalToolPaths, ExternalToolGate externalToolGate,
			FfmpegPhotoHashProcessRunner processRunner, WorkspaceManager workspaceManager) {
		this(externalToolPaths,
				(ffmpegPath, file, metrics) -> externalToolGate.run(ExternalToolCategory.FFMPEG_PHOTO_HASH, metrics,
						() -> processRunner.run(ffmpegPath, file)),
				(ffmpegPath, files, metrics) -> externalToolGate.run(ExternalToolCategory.FFMPEG_PHOTO_HASH, metrics,
						() -> processRunner.runGroup(ffmpegPath, files)),
				workspaceManager);
	}

	PhotoPerceptualHashService(ExternalToolPaths externalToolPaths, FfmpegRunner ffmpegRunner,
			FfmpegGroupRunner ffmpegGroupRunner, WorkspaceManager workspaceManager) {
		this.externalToolPaths = externalToolPaths;
		this.ffmpegRunner = ffmpegRunner;
		this.ffmpegGroupRunner = ffmpegGroupRunner;
		this.workspaceManager = workspaceManager;
	}

	public PhotoPerceptualFingerprint compute(Path file, ProcessingMetrics metrics) {
		FileValidationUtils.validateFile(file);

		rejectZipContainerMasqueradingAsWebp(file);

		byte[] pixels;

		try {
			pixels = hashOf(file, metrics);
		} catch (ExternalToolNotRunnableException exception) {
			// Rethrown whole: wrapping it below would bury the one distinction that
			// matters, and the caller would go on to blame the file for a tool that
			// never ran.
			throw exception;
		} catch (InterruptedException exception) {
			// The flag goes back on before the stack unwinds: a hash interrupted because
			// the run is being cancelled must not read as one that merely failed.
			Thread.currentThread().interrupt();

			throw hashFailure(file, exception);
		} catch (IOException | RuntimeException exception) {
			throw hashFailure(file, exception);
		}

		if (pixels.length != MetadataConstants.SAMPLE_BYTES) {
			throw new IllegalStateException("Unexpected pixel data size computing perceptual hash for file: " + file
					+ ". Expected " + MetadataConstants.SAMPLE_BYTES + " bytes, got " + pixels.length);
		}

		return new PhotoPerceptualFingerprint(PerceptualHashCodec.hash256(pixels), pixels);
	}

	/**
	 * Hashes a whole group of photos with a single ffmpeg invocation, in list
	 * order.
	 *
	 * <p>
	 * What the group removes is process creation, not decoding: against one
	 * invocation per photo it cuts both the wall time and the CPU a photo costs,
	 * and how many photos go in barely moves it - every size measured landed inside
	 * the run-to-run spread of the others. The caller is therefore free to size the
	 * group for other reasons.
	 *
	 * <p>
	 * The result is all or nothing: a count other than the one asked for raises
	 * {@link PhotoHashGroupMismatchException} and nothing is returned, because a
	 * short stream cannot be told apart from a shifted one.
	 */
	public List<PhotoPerceptualFingerprint> computeGroup(List<Path> files, ProcessingMetrics metrics) {
		List<Path> temporary = new ArrayList<>();

		try {
			List<Path> decodable = new ArrayList<>(files.size());

			for (Path file : files) {
				FileValidationUtils.validateFile(file);

				rejectZipContainerMasqueradingAsWebp(file);

				decodable.add(decodableFile(file, temporary));
			}

			return split(ffmpegGroupRunner.run(ffmpegPath(), decodable, metrics), files.size());
		} catch (InterruptedException exception) {
			// Same reason as the single-file path: an interrupted group is a cancelled
			// run, not a group of photos that failed to decode.
			Thread.currentThread().interrupt();

			throw groupFailure(files.size(), exception);
		} catch (IOException exception) {
			throw groupFailure(files.size(), exception);
		} finally {
			deleteQuietly(temporary);
		}
	}

	private IllegalStateException hashFailure(Path file, Exception cause) {
		return new IllegalStateException(
				"Could not run ffmpeg to compute perceptual hash for file: " + file + ". " + cause.getMessage(), cause);
	}

	private IllegalStateException groupFailure(int photos, Exception cause) {
		return new IllegalStateException("Could not run ffmpeg to compute perceptual hashes for a group of " + photos
				+ " photos. " + cause.getMessage(), cause);
	}

	private byte[] hashOf(Path file, ProcessingMetrics metrics) throws IOException, InterruptedException {
		List<Path> temporary = new ArrayList<>(1);

		try {
			return ffmpegRunner.run(ffmpegPath(), decodableFile(file, temporary), metrics);
		} finally {
			deleteQuietly(temporary);
		}
	}

	/**
	 * The file itself, or the part of it ffmpeg can read: the first frame of an
	 * animated sticker, or a photo without the trailer its camera appended. The
	 * slice is handed over as a file because the runner takes a path, and is
	 * deleted straight after - a byte-for-byte slice of the original, regenerated
	 * whenever it is needed again. It is written inside the application workspace,
	 * never in the system temp: that one is world-writable, and a file another user
	 * can swap between the write and the read is a file we should not hand to a
	 * decoder.
	 */
	private Path decodableFile(Path file, List<Path> temporary) throws IOException {
		byte[] decodable = decodableBytes(file);

		if (decodable.length == 0) {
			return file;
		}

		Path slice = Files.createTempFile(workspaceManager.temp(), "decodable", ".tmp");

		temporary.add(slice);

		Files.write(slice, decodable);

		return slice;
	}

	/**
	 * Cuts the stream into one sample per photo, refusing anything but the exact
	 * count. This is the only place the pairing between a photo and its sample is
	 * decided, and position is all there is to decide it with.
	 */
	private static List<PhotoPerceptualFingerprint> split(byte[] pixels, int photos) {
		int expected = photos * MetadataConstants.SAMPLE_BYTES;

		if (pixels.length != expected) {
			throw new PhotoHashGroupMismatchException("Unexpected pixel data size computing perceptual hashes for a "
					+ "group of " + photos + " photos. Expected " + expected + " bytes, got " + pixels.length);
		}

		List<PhotoPerceptualFingerprint> fingerprints = new ArrayList<>(photos);

		for (int index = 0; index < photos; index++) {
			byte[] sample = Arrays.copyOfRange(pixels, index * MetadataConstants.SAMPLE_BYTES,
					(index + 1) * MetadataConstants.SAMPLE_BYTES);

			fingerprints.add(new PhotoPerceptualFingerprint(PerceptualHashCodec.hash256(sample), sample));
		}

		return fingerprints;
	}

	private static void deleteQuietly(List<Path> files) {
		for (Path file : files) {
			try {
				Files.deleteIfExists(file);
			} catch (IOException _) {
				// The workspace temp is cleaned as a whole; failing a hash that already
				// succeeded because a scratch file outlived it would be the worse answer.
			}
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