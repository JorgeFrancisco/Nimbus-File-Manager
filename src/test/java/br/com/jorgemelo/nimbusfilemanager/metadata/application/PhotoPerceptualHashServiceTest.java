package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataConstants;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.PhotoPerceptualFingerprint;
import br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.FfmpegGroupRunner;
import br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.FfmpegRunner;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolPaths;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionMetricsContext;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ProcessingMetrics;

class PhotoPerceptualHashServiceTest {

	/** This test's own accumulator: nothing here is shared with another run. */
	private final ProcessingMetrics metrics = new ExecutionMetricsContext().processing();

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

		PhotoPerceptualHashService service = service((_, _, _) -> pixels);

		PhotoPerceptualFingerprint first = service.compute(file, metrics);
		PhotoPerceptualFingerprint second = service.compute(file, metrics);

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

		byte[] first = service((_, _, _) -> horizontal).compute(file, metrics).hash();
		byte[] second = service((_, _, _) -> vertical).compute(file, metrics).hash();

		assertThat(PhotoPerceptualHashService.distance(first, second)).isPositive();
	}

	@Test
	void computeShouldWrapFfmpegFailures() throws Exception {
		Path file = Files.writeString(tempDir.resolve("broken.jpg"), "not a real image");

		PhotoPerceptualHashService service = service((_, _, _) -> {
			throw new IllegalStateException("ffmpeg exploded");
		});

		assertThatIllegalStateException().isThrownBy(() -> service.compute(file, metrics))
				.withMessageContaining("Could not run ffmpeg");
	}

	@Test
	void computeShouldRejectUnexpectedPixelDataSize() throws Exception {
		Path file = Files.writeString(tempDir.resolve("photo.jpg"), "photo");

		assertThatIllegalStateException().isThrownBy(() -> service((_, _, _) -> new byte[10]).compute(file, metrics))
				.withMessageContaining("Unexpected pixel data size");
	}

	@Test
	void computeRejectsZipPackageMasqueradingAsWebpBeforeStartingFfmpeg() throws Exception {
		Path file = Files.write(tempDir.resolve("sticker.webp"), new byte[] { 'P', 'K', 3, 4, 1, 2 });

		FfmpegRunner runner = mock(FfmpegRunner.class);

		var service = service(runner);

		Assertions.assertThatThrownBy(() -> service.compute(file, metrics))
				.isInstanceOf(UnsupportedPhotoFingerprintException.class).hasMessageContaining("ZIP/Lottie");

		verify(runner, never()).run(any(), any(), any());
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

			Assertions.assertThatThrownBy(() -> service.compute(file, metrics))
					.isInstanceOf(UnsupportedPhotoFingerprintException.class).hasMessageContaining("ZIP/Lottie");

			verify(runner, never()).run(any(), any(), any());
		}
	}

	@Test
	void computeShouldHashAGenuineWebpWhoseHeaderIsNotZip() throws Exception {
		Path file = Files.write(tempDir.resolve("photo.webp"), new byte[] { 'R', 'I', 'F', 'F', 1, 2 });

		PhotoPerceptualFingerprint fingerprint = service((_, _, _) -> gradient()).compute(file, metrics);

		assertThat(fingerprint.hash()).hasSize(32);
	}

	/**
	 * Fewer than four readable bytes cannot be a ZIP header, so the file goes to
	 * ffmpeg and fails (or not) on its own merits instead of being rejected here.
	 */
	@Test
	void computeShouldNotTreatATruncatedWebpAsAZipPackage() throws Exception {
		Path file = Files.write(tempDir.resolve("tiny.webp"), new byte[] { 'P', 'K' });

		PhotoPerceptualFingerprint fingerprint = service((_, _, _) -> gradient()).compute(file, metrics);

		assertThat(fingerprint.hash()).hasSize(32);
	}

	/**
	 * A camera trailer after the image is what defeated ffmpeg, not the photo: the
	 * runner must be handed the image alone.
	 */
	@Test
	void computeDecodesAPhotoWithoutTheTrailerItsCameraAppended() throws Exception {
		byte[] image = concat(new byte[] { (byte) 0xFF, (byte) 0xD8 }, segment((byte) 0xDA, new byte[] { 3, 4 }),
				new byte[] { 1, 2, (byte) 0xFF, (byte) 0xD9 });

		Path panorama = Files.write(tempDir.resolve("panorama.jpg"),
				concat(image, "SEFT".getBytes(StandardCharsets.ISO_8859_1)));

		FfmpegRunner runner = mock(FfmpegRunner.class);

		List<byte[]> handedOver = new ArrayList<>();

		when(runner.run(any(), any(), any())).thenAnswer(invocation -> {
			handedOver.add(Files.readAllBytes(invocation.getArgument(1)));

			return gradient();
		});

		service(runner).compute(panorama, metrics);

		Assertions.assertThat(handedOver).singleElement().isEqualTo(image);
	}

	private static byte[] segment(byte marker, byte[] payload) throws Exception {
		int length = payload.length + 2;

		return concat(new byte[] { (byte) 0xFF, marker, (byte) (length >> 8), (byte) length }, payload);
	}

	/** A minimal animated sticker: extended header, ANIM and one frame. */
	private static byte[] animatedWebp() throws Exception {
		byte[] frame = concat(new byte[16], chunk("ALPH", new byte[] { 1, 2 }),
				chunk("VP8 ", new byte[] { 9, 8, 7, 6 }));

		byte[] body = concat(chunk("VP8X", new byte[10]), chunk("ANIM", new byte[6]), chunk("ANMF", frame));

		return concat("RIFF".getBytes(StandardCharsets.ISO_8859_1), littleEndian(4 + body.length),
				"WEBP".getBytes(StandardCharsets.ISO_8859_1), body);
	}

	private static byte[] chunk(String tag, byte[] payload) throws Exception {
		byte[] padding = (payload.length & 1) == 1 ? new byte[1] : new byte[0];

		return concat(tag.getBytes(StandardCharsets.ISO_8859_1), littleEndian(payload.length), payload, padding);
	}

	private static byte[] concat(byte[]... parts) throws Exception {
		ByteArrayOutputStream all = new ByteArrayOutputStream();

		for (byte[] part : parts) {
			all.write(part);
		}

		return all.toByteArray();
	}

	private static byte[] littleEndian(int value) {
		return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
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

	/**
	 * ffmpeg reads no animation, so an animated sticker is decoded through its
	 * first frame: the runner must be handed that frame, not the original file, and
	 * the frame must be a plain WebP the decoder understands.
	 */
	@Test
	void computeDecodesAnAnimatedStickerThroughItsFirstFrame() throws Exception {
		Path sticker = Files.write(tempDir.resolve("sticker.webp"), animatedWebp());

		FfmpegRunner runner = mock(FfmpegRunner.class);

		List<Path> decoded = new ArrayList<>();
		List<byte[]> handedOver = new ArrayList<>();

		// Read inside the call: the extracted frame is scratch and is gone by the time
		// compute returns, which is what the next test is about.
		when(runner.run(any(), any(), any())).thenAnswer(invocation -> {
			Path given = invocation.getArgument(1);

			decoded.add(given);
			handedOver.add(Files.readAllBytes(given));

			return gradient();
		});

		service(runner).compute(sticker, metrics);

		Assertions.assertThat(decoded).singleElement().isNotEqualTo(sticker);
		Assertions.assertThat(new String(handedOver.getFirst(), 8, 4, StandardCharsets.ISO_8859_1)).isEqualTo("WEBP");
		Assertions.assertThat(new String(handedOver.getFirst(), 12, 4, StandardCharsets.ISO_8859_1)).isEqualTo("VP8 ");
	}

	/** The lifted frame is scratch: it must not be left behind on disk. */
	@Test
	void computeLeavesNoExtractedFrameBehind() throws Exception {
		Path sticker = Files.write(tempDir.resolve("sticker.webp"), animatedWebp());

		FfmpegRunner runner = mock(FfmpegRunner.class);

		ArgumentCaptor<Path> decoded = ArgumentCaptor.forClass(Path.class);

		when(runner.run(any(), decoded.capture(), any())).thenReturn(gradient());

		service(runner).compute(sticker, metrics);

		Assertions.assertThat(Files.exists(decoded.getValue())).isFalse();
	}

	/**
	 * An ffmpeg that never started leaves this method whole, instead of being
	 * folded into the generic "could not run ffmpeg" that every decoding error also
	 * produces. Downstream it is the only thing that keeps a healthy photo from
	 * being written off permanently for a path that pointed at the wrong folder.
	 */
	@Test
	void letsAToolThatNeverStartedThroughUnwrapped() throws Exception {
		Path photo = Files.write(tempDir.resolve("holiday.jpg"), new byte[] { (byte) 0xFF, (byte) 0xD8, 0, 0 });

		FfmpegRunner runner = mock(FfmpegRunner.class);

		when(runner.run(any(), any(), any()))
				.thenThrow(new ExternalToolNotRunnableException("./tools/bin/ffmpeg.exe", new IOException("error=2")));

		PhotoPerceptualHashService service = service(runner);

		Assertions.assertThatThrownBy(() -> service.compute(photo, metrics))
				.isInstanceOf(ExternalToolNotRunnableException.class)
				.hasMessageContaining("./tools/bin/ffmpeg.exe");
	}

	/**
	 * Nothing in the decoded stream names the photo a sample came from: the only
	 * thing pairing the two is the position the sample arrived in.
	 */
	@Test
	void computeGroupPairsEverySampleWithThePhotoInTheSamePosition() throws Exception {
		List<Path> files = List.of(photo("a.jpg"), photo("b.jpg"), photo("c.jpg"));

		byte[] first = filled((byte) 7);
		byte[] second = gradient();
		byte[] third = filled((byte) 200);

		byte[] stream = concat(first, second, third);

		PhotoPerceptualHashService service = service(neverAlone(), (_, _, _) -> stream);

		List<PhotoPerceptualFingerprint> fingerprints = service.computeGroup(files, metrics);

		Assertions.assertThat(fingerprints).hasSize(3);
		Assertions.assertThat(fingerprints.get(0).luminance()).containsExactly(first);
		Assertions.assertThat(fingerprints.get(1).luminance()).containsExactly(second);
		Assertions.assertThat(fingerprints.get(2).luminance()).containsExactly(third);
	}

	/** A photo read in a group and read on its own is the same photo. */
	@Test
	void computeGroupProducesTheSameFingerprintReadingThePhotoAloneDoes() throws Exception {
		Path file = photo("holiday.jpg");

		byte[] sample = gradient();

		PhotoPerceptualFingerprint alone = service((_, _, _) -> sample).compute(file, metrics);

		PhotoPerceptualFingerprint grouped = service(neverAlone(), (_, _, _) -> sample)
				.computeGroup(List.of(file), metrics).getFirst();

		Assertions.assertThat(grouped.hash()).containsExactly(alone.hash());
		Assertions.assertThat(grouped.luminance()).containsExactly(alone.luminance());
	}

	/**
	 * Three photos in, three samples out. A group that came back with two has not
	 * lost the third: it has lost which photo either of the two belongs to, and
	 * keeping the pair that happens to line up would write one photo's fingerprint
	 * against another's name.
	 */
	@Test
	void computeGroupRefusesAGroupThatCameBackShortOfOneSamplePerPhoto() throws Exception {
		List<Path> files = List.of(photo("a.jpg"), photo("b.jpg"), photo("c.jpg"));

		PhotoPerceptualHashService service = service(neverAlone(),
				(_, _, _) -> new byte[2 * MetadataConstants.SAMPLE_BYTES]);

		Assertions.assertThatExceptionOfType(PhotoHashGroupMismatchException.class)
				.isThrownBy(() -> service.computeGroup(files, metrics))
				.withMessageContaining("Expected " + 3 * MetadataConstants.SAMPLE_BYTES + " bytes, got "
						+ 2 * MetadataConstants.SAMPLE_BYTES);
	}

	@Test
	void computeGroupRefusesAGroupThatCameBackWithMoreSamplesThanPhotos() throws Exception {
		List<Path> files = List.of(photo("a.jpg"), photo("b.jpg"), photo("c.jpg"));

		PhotoPerceptualHashService service = service(neverAlone(),
				(_, _, _) -> new byte[4 * MetadataConstants.SAMPLE_BYTES]);

		Assertions.assertThatExceptionOfType(PhotoHashGroupMismatchException.class)
				.isThrownBy(() -> service.computeGroup(files, metrics));
	}

	/**
	 * The boundary between the two: a stream one byte short of three samples is
	 * neither two photos nor three, and slicing it would hand the last photo a
	 * sample it never filled.
	 */
	@Test
	void computeGroupRefusesAStreamThatIsNotAWholeNumberOfSamples() throws Exception {
		List<Path> files = List.of(photo("a.jpg"), photo("b.jpg"), photo("c.jpg"));

		PhotoPerceptualHashService service = service(neverAlone(),
				(_, _, _) -> new byte[3 * MetadataConstants.SAMPLE_BYTES - 1]);

		Assertions.assertThatExceptionOfType(PhotoHashGroupMismatchException.class)
				.isThrownBy(() -> service.computeGroup(files, metrics));
	}

	@Test
	void computeGroupAsksTheToolOnceForTheWholeGroup() throws Exception {
		List<Path> files = List.of(photo("a.jpg"), photo("b.jpg"), photo("c.jpg"));

		AtomicInteger invocations = new AtomicInteger();

		PhotoPerceptualHashService service = service(neverAlone(), (_, decoded, _) -> {
			invocations.incrementAndGet();

			return new byte[decoded.size() * MetadataConstants.SAMPLE_BYTES];
		});

		service.computeGroup(files, metrics);

		Assertions.assertThat(invocations).hasValue(1);
	}

	/** In the order given, since the order is what names the samples. */
	@Test
	void computeGroupHandsTheToolEveryPhotoInTheOrderItWasGiven() throws Exception {
		List<Path> files = List.of(photo("a.jpg"), photo("b.jpg"), photo("c.jpg"));

		AtomicReference<List<Path>> handedOver = new AtomicReference<>();

		PhotoPerceptualHashService service = service(neverAlone(), (_, decoded, _) -> {
			handedOver.set(decoded);

			return new byte[3 * MetadataConstants.SAMPLE_BYTES];
		});

		service.computeGroup(files, metrics);

		Assertions.assertThat(handedOver.get()).containsExactlyElementsOf(files);
	}

	/**
	 * A sticker is decoded from the frame pulled out of it, not from the file the
	 * decoder refuses - in a group as much as on its own - and the slice that was
	 * written to hand it over does not outlive the call.
	 */
	@Test
	void computeGroupDecodesTheExtractedFrameAndLeavesNothingBehind() throws Exception {
		Path sticker = Files.write(tempDir.resolve("sticker.webp"), animatedWebp());

		AtomicReference<List<Path>> handedOver = new AtomicReference<>();

		PhotoPerceptualHashService service = service(neverAlone(), (_, decoded, _) -> {
			handedOver.set(decoded);

			return new byte[MetadataConstants.SAMPLE_BYTES];
		});

		service.computeGroup(List.of(sticker), metrics);

		Assertions.assertThat(handedOver.get()).singleElement().isNotEqualTo(sticker);
		Assertions.assertThat(Files.exists(handedOver.get().getFirst())).isFalse();
	}

	private Path photo(String name) throws IOException {
		return Files.writeString(tempDir.resolve(name), name);
	}

	private static FfmpegRunner neverAlone() {
		return (_, _, _) -> {
			throw new UnsupportedOperationException("This test never reads a photo on its own");
		};
	}

	private static byte[] filled(byte value) {
		byte[] sample = new byte[MetadataConstants.SAMPLE_BYTES];

		Arrays.fill(sample, value);

		return sample;
	}

	private PhotoPerceptualHashService service(FfmpegRunner runner) {
		return service(runner, (_, _, _) -> {
			throw new UnsupportedOperationException("This test never asks for a group");
		});
	}

	private PhotoPerceptualHashService service(FfmpegRunner runner, FfmpegGroupRunner groupRunner) {
		ExternalToolPaths externalToolPaths = mock(ExternalToolPaths.class);

		lenient().when(externalToolPaths.ffmpeg()).thenReturn("ffmpeg");

		WorkspaceManager workspaceManager = mock(WorkspaceManager.class);

		lenient().when(workspaceManager.temp()).thenReturn(tempDir);

		return new PhotoPerceptualHashService(externalToolPaths, runner, groupRunner, workspaceManager);
	}
}