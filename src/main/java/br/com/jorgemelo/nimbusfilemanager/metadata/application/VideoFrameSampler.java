package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoFrameSamplingPlan;

/**
 * Turns a video's duration into a deterministic frame-sampling plan: {@code n}
 * frames at the centered relative positions {@code (i + 0.5) / n} of the
 * duration (for {@code n = 5}: 10%, 30%, 50%, 70%, 90%). Centering avoids the
 * pure first/last frame (black intro, credits) while spreading the samples
 * across the whole video.
 *
 * <p>
 * Because the positions are <b>relative</b>, two videos of slightly different
 * duration sample almost the same content at each {@code sampleIndex}, which is
 * what makes the comparison robust to small duration differences. The plan
 * drives a single ffmpeg pass that selects one frame at each exact timestamp
 * (via a {@code select} expression), never a fixed frequency.
 */
@Component
public class VideoFrameSampler {

	public VideoFrameSamplingPlan plan(double durationSeconds, int frameCount) {
		if (!Double.isFinite(durationSeconds) || durationSeconds <= 0) {
			throw new IllegalArgumentException("Video duration must be a positive number of seconds");
		}

		if (frameCount < 1) {
			throw new IllegalArgumentException("Frame count must be at least 1");
		}

		List<Long> positionsMs = new ArrayList<>(frameCount);

		for (int index = 0; index < frameCount; index++) {
			double positionSeconds = (index + 0.5) * durationSeconds / frameCount;

			positionsMs.add(Math.round(positionSeconds * 1000));
		}

		return new VideoFrameSamplingPlan(positionsMs);
	}
}