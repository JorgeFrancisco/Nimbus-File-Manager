package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataConstants.SAMPLE_BYTES;
import static br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataConstants.SAMPLE_SIDE;

import org.springframework.stereotype.Service;

/**
 * Confirms pHash candidates with windowed SSIM over their persisted 32x32
 * luminance samples. The returned percentage is the value shown to users; pHash
 * distance is deliberately never presented as a percentage.
 *
 * <p>
 * It knows nothing about the medium the sample came from, and both media rely on
 * that: a photo is confirmed by one call, a video by one call per aligned frame.
 * The name says luminance rather than photo for the same reason - what it takes
 * is two 32x32 samples, and where they were decoded from is the caller's
 * business.
 */
@Service
public class LuminanceSsimService {

	private static final int WINDOW_SIDE = 8;
	private static final int WINDOWS_PER_AXIS = SAMPLE_SIDE / WINDOW_SIDE;
	private static final int WINDOW_COUNT = WINDOWS_PER_AXIS * WINDOWS_PER_AXIS;
	private static final int WINDOW_SAMPLE_COUNT = WINDOW_SIDE * WINDOW_SIDE;

	private static final double C1 = Math.pow(0.01 * 255, 2);
	private static final double C2 = Math.pow(0.03 * 255, 2);

	public int similarityPercent(byte[] first, byte[] second) {
		validateSamples(first, second);

		double total = 0;

		for (int row = 0; row < SAMPLE_SIDE; row += WINDOW_SIDE) {
			for (int column = 0; column < SAMPLE_SIDE; column += WINDOW_SIDE) {
				total += windowSsim(first, second, row, column);
			}
		}

		double score = Math.clamp(total / WINDOW_COUNT, 0, 1);

		return (int) Math.round(score * 100);
	}

	private void validateSamples(byte[] first, byte[] second) {
		if (first == null || second == null || first.length != SAMPLE_BYTES || second.length != SAMPLE_BYTES) {
			throw new IllegalArgumentException("SSIM requires two 32x32 luminance samples");
		}
	}

	private double windowSsim(byte[] first, byte[] second, int startRow, int startColumn) {
		double firstMean = 0;
		double secondMean = 0;

		for (int row = startRow; row < startRow + WINDOW_SIDE; row++) {
			for (int column = startColumn; column < startColumn + WINDOW_SIDE; column++) {
				int index = row * SAMPLE_SIDE + column;

				firstMean += first[index] & 0xFF;
				secondMean += second[index] & 0xFF;
			}
		}

		firstMean /= WINDOW_SAMPLE_COUNT;
		secondMean /= WINDOW_SAMPLE_COUNT;

		double firstVariance = 0;
		double secondVariance = 0;
		double covariance = 0;

		for (int row = startRow; row < startRow + WINDOW_SIDE; row++) {
			for (int column = startColumn; column < startColumn + WINDOW_SIDE; column++) {
				int index = row * SAMPLE_SIDE + column;

				double firstDelta = (first[index] & 0xFF) - firstMean;
				double secondDelta = (second[index] & 0xFF) - secondMean;

				firstVariance += firstDelta * firstDelta;
				secondVariance += secondDelta * secondDelta;
				covariance += firstDelta * secondDelta;
			}
		}

		int varianceDivisor = WINDOW_SAMPLE_COUNT - 1;

		firstVariance /= varianceDivisor;
		secondVariance /= varianceDivisor;
		covariance /= varianceDivisor;

		double luminanceNumerator = 2 * firstMean * secondMean + C1;
		double luminanceDenominator = firstMean * firstMean + secondMean * secondMean + C1;
		double luminance = luminanceNumerator / luminanceDenominator;

		double structureNumerator = 2 * covariance + C2;
		double structureDenominator = firstVariance + secondVariance + C2;
		double structure = structureNumerator / structureDenominator;

		return luminance * structure;
	}
}