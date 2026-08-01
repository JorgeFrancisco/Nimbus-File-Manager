package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.CommandOptions;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.TranscodeExecution;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.TranscodeRequest;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.TranscodeResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionFailure;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ExternalToolGate;
import br.com.jorgemelo.nimbusfilemanager.processing.domain.enums.ExternalToolCategory;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns one video into a validated H.265/MP4 file. Everything that can go wrong
 * stays inside this class: the temporary file, the retries that give up what
 * MP4 cannot hold, and the validation that decides whether the output may be
 * kept at all. The caller receives either a validated file (still under its
 * temporary name) or the reason it does not have one - it never has to
 * interpret an ffmpeg exit code.
 *
 * <p>
 * At most three attempts are made, each dropping one demand the container
 * refused: audio re-encoded to AAC, then subtitles left behind. Anything else
 * fails on the first attempt, because no further attempt could fix it.
 *
 * <p>
 * A failed or cancelled attempt always deletes its output. That is what makes
 * the whole feature safe to retry: it leaves the library exactly as it was,
 * with nothing half-written anywhere.
 */
@Slf4j
@Component
public class VideoTranscoder {

	private final VideoConversionCommandBuilder commandBuilder;
	private final VideoTranscodeRunner transcodeRunner;
	private final ConvertedVideoValidator validator;
	private final StreamCompatibilityPolicy streamCompatibilityPolicy;
	private final ConversionFileNaming conversionFileNaming;
	private final FfmpegProgressParser progressParser;
	private final ExternalToolGate externalToolGate;

	public VideoTranscoder(VideoConversionCommandBuilder commandBuilder, VideoTranscodeRunner transcodeRunner,
			ConvertedVideoValidator validator, StreamCompatibilityPolicy streamCompatibilityPolicy,
			ConversionFileNaming conversionFileNaming, FfmpegProgressParser progressParser,
			ExternalToolGate externalToolGate) {
		this.commandBuilder = commandBuilder;
		this.transcodeRunner = transcodeRunner;
		this.validator = validator;
		this.streamCompatibilityPolicy = streamCompatibilityPolicy;
		this.conversionFileNaming = conversionFileNaming;
		this.progressParser = progressParser;
		this.externalToolGate = externalToolGate;
	}

	/**
	 * Converts {@code request} and reports the progress of this single file (0-100)
	 * to {@code onFilePercent} while ffmpeg runs.
	 */
	public TranscodeResult transcode(TranscodeRequest request, IntConsumer onFilePercent, BooleanSupplier cancelled) {
		long start = System.nanoTime();

		Path output = conversionFileNaming.temporaryFor(request.source(), request.options());

		return encodeAndValidate(request, output, onFilePercent, cancelled, start);
	}

	private TranscodeResult encodeAndValidate(TranscodeRequest request, Path output, IntConsumer onFilePercent,
			BooleanSupplier cancelled, long start) {
		CommandOptions options = new CommandOptions(request.options().quality(), request.sourceIsHevc(),
				streamCompatibilityPolicy.encodesAacUpFront(request.options().audio()), true, true);

		TranscodeExecution execution = run(request, output, options, onFilePercent, cancelled);

		if (retriesWithAac(request, execution, cancelled)) {
			log.info("Retrying {} with AAC audio: MP4 does not accept the original audio stream", request.source());

			options = new CommandOptions(options.quality(), options.copyVideo(), true, options.includeSubtitles(),
					options.includeData());

			execution = run(request, output, options, onFilePercent, cancelled);
		}

		if (retriesWithoutData(execution, cancelled)) {
			// Action cameras carry telemetry and timecode tracks that MP4 has no tag for.
			// The muxer refuses them before the first frame is encoded, so the video is
			// perfectly convertible - it just has to travel without them.
			log.info("Retrying {} without the data tracks: MP4 cannot hold this camera's telemetry", request.source());

			options = new CommandOptions(options.quality(), options.copyVideo(), options.encodeAudioAsAac(),
					options.includeSubtitles(), false);

			execution = run(request, output, options, onFilePercent, cancelled);
		}

		if (retriesOnSoftware(options, execution, cancelled)) {
			log.warn("Retrying {} on the software encoder: the hardware one refused this file", request.source());

			// A GPU encoder can accept a session and still refuse a particular file
			// (unsupported pixel format, resolution beyond the block's limits). Falling
			// back to software costs time but keeps the batch moving, exactly like the
			// audio fallback does.
			options = new CommandOptions(options.quality().softwareEquivalent(), options.copyVideo(),
					options.encodeAudioAsAac(), options.includeSubtitles(), options.includeData());

			execution = run(request, output, options, onFilePercent, cancelled);
		}

		if (retriesWithoutSubtitles(execution, cancelled)) {
			log.info("Retrying {} without subtitles: MP4 cannot hold the subtitle track of this file",
					request.source());

			options = new CommandOptions(options.quality(), options.copyVideo(), options.encodeAudioAsAac(), false,
					options.includeData());

			execution = run(request, output, options, onFilePercent, cancelled);
		}

		if (cancelled.getAsBoolean()) {
			// The half-written file goes with it; the source was never touched.
			conversionFileNaming.discard(output);

			return TranscodeResult.failed(ConversionFailure.CANCELLED, false, false, false, elapsedMillis(start));
		}

		return validate(request, output, options, execution, start);
	}

	/** A cancelled batch never spends another full encode on a retry. */
	private boolean retriesWithAac(TranscodeRequest request, TranscodeExecution execution, BooleanSupplier cancelled) {
		return !cancelled.getAsBoolean() && !execution.successful()
				&& streamCompatibilityPolicy.shouldRetryWithAac(request.options().audio(), execution.errorOutput());
	}

	/** Only a hardware attempt has a slower encoder left to fall back to. */
	private boolean retriesOnSoftware(CommandOptions options, TranscodeExecution execution, BooleanSupplier cancelled) {
		return !cancelled.getAsBoolean() && !execution.successful() && !options.copyVideo()
				&& options.quality().requiresHardware();
	}

	/** Only worth another attempt while the data tracks are still being mapped. */
	private boolean retriesWithoutData(TranscodeExecution execution, BooleanSupplier cancelled) {
		return !cancelled.getAsBoolean() && !execution.successful()
				&& streamCompatibilityPolicy.shouldRetryWithoutData(execution.errorOutput());
	}

	private boolean retriesWithoutSubtitles(TranscodeExecution execution, BooleanSupplier cancelled) {
		return !cancelled.getAsBoolean() && !execution.successful()
				&& streamCompatibilityPolicy.shouldRetryWithoutSubtitles(execution.errorOutput());
	}

	private TranscodeResult validate(TranscodeRequest request, Path output, CommandOptions options,
			TranscodeExecution execution, long start) {
		boolean audioFallback = options.encodeAudioAsAac()
				&& !streamCompatibilityPolicy.encodesAacUpFront(request.options().audio());

		boolean subtitlesDropped = !options.includeSubtitles();

		boolean dataDropped = !options.includeData();

		if (!execution.successful()) {
			log.warn("ffmpeg could not convert {} (finished={}, exit={}): {}", request.source(), execution.finished(),
					execution.exitCode(), execution.errorOutput());

			conversionFileNaming.discard(output);

			return TranscodeResult.failed(ConversionFailure.ENCODER_FAILED, audioFallback, subtitlesDropped,
					dataDropped, elapsedMillis(start));
		}

		Optional<ConversionFailure> rejected = validator.validate(output, request.sourceDurationSeconds());

		if (rejected.isPresent()) {
			// ffmpeg reported success and the result still could not be trusted, so what
			// it said on the way is the only evidence there is. Without it, a rejection
			// that happened once in three hundred files took a manual re-run of the same
			// command to rule out a locked source and a broken input - both of which
			// ffmpeg had already answered here, into nothing.
			log.warn("The conversion of {} was rejected by validation: {} (ffmpeg exit={}, output at {}): {}",
					request.source(), rejected.get(), execution.exitCode(), output, execution.errorOutput());

			conversionFileNaming.discard(output);

			return TranscodeResult.failed(rejected.get(), audioFallback, subtitlesDropped, dataDropped,
					elapsedMillis(start));
		}

		return TranscodeResult.converted(output, audioFallback, subtitlesDropped, dataDropped, elapsedMillis(start));
	}

	private TranscodeExecution run(TranscodeRequest request, Path output, CommandOptions options,
			IntConsumer onFilePercent, BooleanSupplier cancelled) {
		List<String> command = commandBuilder.build(request.source(), output, options);

		try {
			// The gate is what keeps a transcode - minutes of saturated CPU - from
			// running alongside another one or piling on top of an inventory's own ffmpeg
			// work.
			return externalToolGate.run(ExternalToolCategory.FFMPEG_TRANSCODE,
					() -> transcodeRunner.run(command, line -> report(line, request, onFilePercent), cancelled));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();

			return new TranscodeExecution(false, -1, e.getMessage());
		} catch (Exception e) {
			log.error("Could not run ffmpeg to convert {}", request.source(), e);

			return new TranscodeExecution(false, -1, e.getMessage());
		}
	}

	private void report(String line, TranscodeRequest request, IntConsumer onFilePercent) {
		OptionalLong elapsed = progressParser.elapsedMicroseconds(line);

		if (elapsed.isPresent()) {
			onFilePercent.accept(progressParser.percent(elapsed.getAsLong(), request.sourceDurationSeconds()));
		}
	}

	private long elapsedMillis(long start) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
	}
}