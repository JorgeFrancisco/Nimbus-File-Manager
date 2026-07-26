package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.PhotoPerceptualFingerprint;
import br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.FfmpegRunner;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolPaths;

class PhotoPerceptualHashServiceTest {

	@TempDir
	Path tempDir;

	@Test
	void distanceShouldCountDifferingBitsAcrossAll256Bits() {
		byte[] clear = new byte[32];
		byte[] fourBits = clear.clone();

		fourBits[0] = 0x0F;

		assertThat(PhotoPerceptualHashService.distance(clear, clear)).isZero();
		assertThat(PhotoPerceptualHashService.distance(clear, fourBits)).isEqualTo(4);

		byte[] set = new byte[32];

		Arrays.fill(set, (byte) 0xFF);

		assertThat(PhotoPerceptualHashService.distance(clear, set)).isEqualTo(256);
	}

	@Test
	void computeBuildsDeterministic256BitPHashAndKeepsLuminanceSample() throws Exception {
		Path file = Files.writeString(tempDir.resolve("photo.jpg"), "photo");

		byte[] pixels = gradient();

		PhotoPerceptualHashService service = service((_, _) -> pixels);

		PhotoPerceptualFingerprint first = service.compute(file);
		PhotoPerceptualFingerprint second = service.compute(file);

		assertThat(first.hash()).hasSize(32).containsExactly(second.hash());
		assertThat(first.luminance()).hasSize(1024).containsExactly(pixels);
	}

	@Test
	void differentLuminanceStructuresProduceDifferentPHashes() throws Exception {
		Path file = Files.writeString(tempDir.resolve("photo.jpg"), "photo");

		byte[] horizontal = gradient();
		byte[] vertical = new byte[1024];

		for (int row = 0; row < 32; row++) {
			Arrays.fill(vertical, row * 32, row * 32 + 32, (byte) (row * 8));
		}

		byte[] first = service((_, _) -> horizontal).compute(file).hash();
		byte[] second = service((_, _) -> vertical).compute(file).hash();

		assertThat(PhotoPerceptualHashService.distance(first, second)).isPositive();
	}

	@Test
	void computeShouldWrapFfmpegFailures() throws Exception {
		Path file = Files.writeString(tempDir.resolve("broken.jpg"), "not a real image");

		PhotoPerceptualHashService service = service((_, _) -> {
			throw new IllegalStateException("ffmpeg exploded");
		});

		assertThatIllegalStateException().isThrownBy(() -> service.compute(file))
				.withMessageContaining("Could not run ffmpeg");
	}

	@Test
	void computeShouldRejectUnexpectedPixelDataSize() throws Exception {
		Path file = Files.writeString(tempDir.resolve("photo.jpg"), "photo");

		assertThatIllegalStateException().isThrownBy(() -> service((_, _) -> new byte[10]).compute(file))
				.withMessageContaining("Unexpected pixel data size");
	}

	@Test
	void computeRejectsZipPackageMasqueradingAsWebpBeforeStartingFfmpeg() throws Exception {
		Path file = Files.write(tempDir.resolve("sticker.webp"), new byte[] { 'P', 'K', 3, 4, 1, 2 });

		FfmpegRunner runner = mock(FfmpegRunner.class);

		var service = service(runner);

		Assertions.assertThatThrownBy(() -> service.compute(file))
				.isInstanceOf(UnsupportedPhotoFingerprintException.class).hasMessageContaining("ZIP/Lottie");

		verify(runner, never()).run(any(), any());
	}

	/**
	 * A Lottie/ZIP sticker can carry any of the three ZIP local-header spellings,
	 * so all of them must be refused before ffmpeg is ever started.
	 */
	@Test
	void computeRejectsEveryZipHeaderSpellingInsideAWebp() throws Exception {
		byte[][] signatures = { { 'P', 'K', 3, 4 }, { 'P', 'K', 5, 6 }, { 'P', 'K', 7, 8 } };

		for (byte[] signature : signatures) {
			Path file = Files.write(tempDir.resolve("sticker-" + signature[2] + ".webp"), signature);

			FfmpegRunner runner = mock(FfmpegRunner.class);

			var service = service(runner);

			Assertions.assertThatThrownBy(() -> service.compute(file))
					.isInstanceOf(UnsupportedPhotoFingerprintException.class).hasMessageContaining("ZIP/Lottie");

			verify(runner, never()).run(any(), any());
		}
	}

	@Test
	void computeShouldHashAGenuineWebpWhoseHeaderIsNotZip() throws Exception {
		Path file = Files.write(tempDir.resolve("photo.webp"), new byte[] { 'R', 'I', 'F', 'F', 1, 2 });

		PhotoPerceptualFingerprint fingerprint = service((_, _) -> gradient()).compute(file);

		assertThat(fingerprint.hash()).hasSize(32);
	}

	/**
	 * Fewer than four readable bytes cannot be a ZIP header, so the file goes to
	 * ffmpeg and fails (or not) on its own merits instead of being rejected here.
	 */
	@Test
	void computeShouldNotTreatATruncatedWebpAsAZipPackage() throws Exception {
		Path file = Files.write(tempDir.resolve("tiny.webp"), new byte[] { 'P', 'K' });

		PhotoPerceptualFingerprint fingerprint = service((_, _) -> gradient()).compute(file);

		assertThat(fingerprint.hash()).hasSize(32);
	}

	private byte[] gradient() {
		byte[] pixels = new byte[1024];

		for (int row = 0; row < 32; row++) {
			for (int column = 0; column < 32; column++) {
				pixels[row * 32 + column] = (byte) (column * 8);
			}
		}

		return pixels;
	}

	private PhotoPerceptualHashService service(FfmpegRunner runner) {
		ExternalToolPaths externalToolPaths = mock(ExternalToolPaths.class);

		lenient().when(externalToolPaths.ffmpeg()).thenReturn("ffmpeg");

		return new PhotoPerceptualHashService(externalToolPaths, runner);
	}
}