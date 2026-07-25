package br.com.jorgemelo.nimbusfilemanager.metadata.application.dto;

import java.util.List;

/**
 * How a single ffmpeg pass should sample a video: the exact timestamps
 * ({@code positionsMs}) of the frames to extract, at deterministic relative
 * positions of the duration. A {@code select} expression built from these
 * timestamps picks exactly one frame at (or just after) each of them - never a
 * fixed frequency. The frame count is {@code positionsMs.size()}.
 */
public record VideoFrameSamplingPlan(List<Long> positionsMs) {

	public VideoFrameSamplingPlan {
		if (positionsMs == null || positionsMs.isEmpty()) {
			throw new IllegalArgumentException("A sampling plan needs at least one frame position");
		}

		positionsMs = List.copyOf(positionsMs);
	}

	public int frameCount() {
		return positionsMs.size();
	}
}