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
import br.com.jorgemelo.nimbusfilemanager.shared.util.FileValidationUtils;

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

	private final ExternalToolPaths externalToolPaths;
	private final VideoFrameSampler videoFrameSampler;
	private final FfmpegVideoFrameRunner ffmpegRunner;

	@Autowired
	public VideoPerceptualHashService(ExternalToolPaths externalToolPaths, VideoFrameSampler videoFrameSampler,
			ExternalToolGate externalToolGate, FfmpegVideoFrameProcessRunner processRunner) {
		this(externalToolPaths, videoFrameSampler,
				(ffmpegPath, file, plan) -> externalToolGate.run(ExternalToolCategory.FFMPEG_VIDEO_FRAME,
						() -> processRunner.run(ffmpegPath, file, plan)));
	}

	VideoPerceptualHashService(ExternalToolPaths externalToolPaths, VideoFrameSampler videoFrameSampler,
			FfmpegVideoFrameRunner ffmpegRunner) {
		this.externalToolPaths = externalToolPaths;
		this.videoFrameSampler = videoFrameSampler;
		this.ffmpegRunner = ffmpegRunner;
	}

	public VideoPerceptualFingerprint compute(Path file, Double durationSeconds, int frameCount) {
		FileValidationUtils.validateFile(file);

		if (durationSeconds == null || !Double.isFinite(durationSeconds) || durationSeconds <= 0) {
			throw new UnsupportedVideoFingerprintException(
					"Video has no usable duration to sample frames from: " + file);
		}

		VideoFrameSamplingPlan plan = videoFrameSampler.plan(durationSeconds, frameCount);

		byte[] frames = sample(file, plan);

		if (frames.length == 0 || frames.length % MetadataConstants.SAMPLE_BYTES != 0) {
			throw new UnsupportedVideoFingerprintException("FFmpeg returned no decodable frames for video: " + file
					+ " (got " + frames.length + " bytes)");
		}

		return new VideoPerceptualFingerprint(toFrameFingerprints(frames, plan));
	}

	private byte[] sample(Path file, VideoFrameSamplingPlan plan) {
		try {
			return ffmpegRunner.run(ffmpegPath(), file, plan);
		} catch (Exception exception) {
			throw new IllegalStateException("Could not run ffmpeg to sample video frames for file: " + file + ". "
					+ exception.getMessage(), exception);
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