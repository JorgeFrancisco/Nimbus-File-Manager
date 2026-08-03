package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataConstants;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoFrameFingerprint;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoFrameSamplingPlan;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoPerceptualFingerprint;
import br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.FfmpegVideoFrameProcessRunner;
import br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.FfmpegVideoFrameRunner;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ExternalToolGate;
import br.com.jorgemelo.nimbusfilemanager.processing.domain.enums.ExternalToolCategory;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolPaths;
import br.com.jorgemelo.nimbusfilemanager.shared.application.CoverageGenerated;

/**
 * Computes a video's multi-frame perceptual fingerprint: a single ffmpeg pass
 * samples {@code frameCount} frames at deterministic relative positions, and
 * each frame is turned into the same 256-bit pHash + 32x32 luminance sample as
 * a photo via {@link PerceptualHashCodec}. The math is shared, only the frame
 * selection is video-specific. {@code frameCount} is supplied by the algorithm
 * (part of its identity), never a runtime setting, so a stored fingerprint's
 * frame alignment can never drift under it.
 */
@Service
public class VideoPerceptualHashService {

	/** The plan a single-frame video ends up with: one sample, at the start. */
	private static final VideoFrameSamplingPlan SINGLE_FRAME = new VideoFrameSamplingPlan(List.of(0L));

	private final ExternalToolPaths externalToolPaths;
	private final VideoFrameSampler videoFrameSampler;
	private final FfmpegVideoFrameRunner ffmpegRunner;
	private final PhotoPerceptualHashService photoPerceptualHashService;

	@Autowired
	@CoverageGenerated("Spring wiring: forwards to the constructor every test builds directly")
	public VideoPerceptualHashService(ExternalToolPaths externalToolPaths, VideoFrameSampler videoFrameSampler,
			ExternalToolGate externalToolGate, FfmpegVideoFrameProcessRunner processRunner,
			PhotoPerceptualHashService photoPerceptualHashService) {
		this(externalToolPaths, videoFrameSampler, (ffmpegPath, file, plan) -> externalToolGate
				.run(ExternalToolCategory.FFMPEG_VIDEO_FRAME, () -> processRunner.run(ffmpegPath, file, plan)),
				photoPerceptualHashService);
	}

	VideoPerceptualHashService(ExternalToolPaths externalToolPaths, VideoFrameSampler videoFrameSampler,
			FfmpegVideoFrameRunner ffmpegRunner, PhotoPerceptualHashService photoPerceptualHashService) {
		this.externalToolPaths = externalToolPaths;
		this.videoFrameSampler = videoFrameSampler;
		this.ffmpegRunner = ffmpegRunner;
		this.photoPerceptualHashService = photoPerceptualHashService;
	}

	public VideoPerceptualFingerprint compute(Path file, Double durationSeconds, int frameCount) {
		FileValidationUtils.validateFile(file);

		if (durationSeconds == null || !Double.isFinite(durationSeconds) || durationSeconds <= 0) {
			throw new UnsupportedVideoFingerprintException(
					"Video has no usable duration to sample frames from: " + file);
		}

		VideoFrameSamplingPlan plan = videoFrameSampler.plan(durationSeconds, frameCount);

		byte[] frames = sample(file, plan);

		// A video of a single frame - the clip a phone keeps beside a photo, or a
		// recording stopped the instant it started - has nothing at the sampled
		// timestamps, so sampling comes back empty. Its one frame is still a picture,
		// and hashing it is what the photos do.
		if (frames.length == 0) {
			frames = firstFrame(file);
			plan = SINGLE_FRAME;
		}

		// Only the size is checked here: an empty result already went through the
		// fallback above, which either produced a frame or refused the file.
		if (frames.length % MetadataConstants.SAMPLE_BYTES != 0) {
			throw new UnsupportedVideoFingerprintException("FFmpeg returned frames of unexpected size for video: "
					+ file + " (got " + frames.length + " bytes)");
		}

		return new VideoPerceptualFingerprint(toFrameFingerprints(frames, plan));
	}

	/**
	 * The opening frame, read the way a photo is: no timestamp selection, because a
	 * one-frame video holds nothing at the positions a plan asks for. Reusing the
	 * photo service is the point - "treat it as an image" is exactly what this
	 * fallback means, and that path is already wired and tested.
	 */
	private byte[] firstFrame(Path file) {
		try {
			return photoPerceptualHashService.compute(file).luminance();
		} catch (RuntimeException failure) {
			// Kept as a video failure on purpose: the reason recorded for a video has to
			// read as one, and the photo path speaks about pixels and images.
			throw new UnsupportedVideoFingerprintException(
					"Video holds a single frame that could not be decoded: " + file + ". " + failure.getMessage());
		}
	}

	private byte[] sample(Path file, VideoFrameSamplingPlan plan) {
		try {
			return ffmpegRunner.run(ffmpegPath(), file, plan);
		} catch (ExternalToolNotRunnableException exception) {
			// Rethrown whole: wrapping it below would bury the one distinction that
			// matters, and the caller would go on to blame the file for a tool that
			// never ran.
			throw exception;
		} catch (Exception exception) {
			throw new IllegalStateException(
					"Could not run ffmpeg to sample video frames for file: " + file + ". " + exception.getMessage(),
					exception);
		}
	}

	private List<VideoFrameFingerprint> toFrameFingerprints(byte[] frames, VideoFrameSamplingPlan plan) {
		int available = frames.length / MetadataConstants.SAMPLE_BYTES;

		int count = Math.min(available, plan.frameCount());

		List<VideoFrameFingerprint> fingerprints = new ArrayList<>(count);

		for (int index = 0; index < count; index++) {
			int start = index * MetadataConstants.SAMPLE_BYTES;

			byte[] sample = Arrays.copyOfRange(frames, start, start + MetadataConstants.SAMPLE_BYTES);

			fingerprints.add(new VideoFrameFingerprint(index, plan.positionsMs().get(index),
					PerceptualHashCodec.hash256(sample), sample));
		}

		return fingerprints;
	}

	private String ffmpegPath() {
		return externalToolPaths.ffmpeg();
	}
}