package br.com.jorgemelo.nimbusfilemanager.metadata.application.dto;

import java.util.List;

/**
 * The ordered set of frame fingerprints for one video. Frames are aligned by
 * {@link VideoFrameFingerprint#sampleIndex()} (a deterministic relative
 * position), so two videos are compared frame-for-frame at the same relative
 * position regardless of their exact duration.
 */
public record VideoPerceptualFingerprint(List<VideoFrameFingerprint> frames) {

	public VideoPerceptualFingerprint {
		if (frames == null || frames.isEmpty()) {
			throw new IllegalArgumentException("A video fingerprint needs at least one frame");
		}

		frames = List.copyOf(frames);
	}
}