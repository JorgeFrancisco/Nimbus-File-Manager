package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoFrameSamplingPlan;

class VideoFrameSamplerTest {

	private final VideoFrameSampler sampler = new VideoFrameSampler();

	@Test
	void placesFramesAtCenteredRelativePositions() {
		VideoFrameSamplingPlan plan = sampler.plan(10.0, 5);

		// (i + 0.5) / 5 of 10s -> 10%, 30%, 50%, 70%, 90%.
		assertThat(plan.positionsMs()).containsExactly(1000L, 3000L, 5000L, 7000L, 9000L);
		assertThat(plan.frameCount()).isEqualTo(5);
	}

	@Test
	void relativePositionsScaleWithDuration() {
		VideoFrameSamplingPlan shorter = sampler.plan(20.0, 5);
		VideoFrameSamplingPlan longer = sampler.plan(200.0, 5);

		// Same relative fractions, just scaled: the last frame is always at 90%.
		assertThat(shorter.positionsMs()).containsExactly(2000L, 6000L, 10000L, 14000L, 18000L);
		assertThat(longer.positionsMs().getLast()).isEqualTo(180000L);
	}

	@Test
	void rejectsNonPositiveDuration() {
		assertThatThrownBy(() -> sampler.plan(0.0, 5)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> sampler.plan(-1.0, 5)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> sampler.plan(Double.NaN, 5)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsNonPositiveFrameCount() {
		assertThatThrownBy(() -> sampler.plan(10.0, 0)).isInstanceOf(IllegalArgumentException.class);
	}
}