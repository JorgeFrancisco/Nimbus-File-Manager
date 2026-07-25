package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import java.util.Arrays;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataConstants;

/**
 * Neutral pHash math shared by every visual fingerprint, whatever the media it
 * came from: a separable DCT over a normalized 32x32 grayscale sample, whose
 * 16x16 low-frequency block is thresholded against its AC median to form a
 * packed 256-bit hash, plus the Hamming distance between two such hashes.
 *
 * <p>
 * It works on a decoded 1024-byte sample and knows nothing about ffmpeg,
 * photos, videos or frames - a photo passes its single frame, a video passes
 * each sampled frame. Keeping the math here (rather than inside a photo
 * service) is what lets the video algorithm reuse it without duplicating a
 * single line.
 */
public final class PerceptualHashCodec {

	static final int LOW_FREQUENCY_SIDE = 16;
	static final int HASH_BITS = LOW_FREQUENCY_SIDE * LOW_FREQUENCY_SIDE;
	private static final double[][] COSINES = cosineTable();

	private PerceptualHashCodec() {
	}

	/** Packs the 256-bit DCT pHash of a normalized 32x32 grayscale sample. */
	public static byte[] hash256(byte[] pixels) {
		if (pixels == null || pixels.length != MetadataConstants.SAMPLE_BYTES) {
			throw new IllegalArgumentException("pHash requires a 1024-byte 32x32 luminance sample");
		}

		double[] coefficients = columnPass(rowPass(pixels));

		double[] ac = Arrays.copyOfRange(coefficients, 1, coefficients.length);
		Arrays.sort(ac);

		double median = ac[ac.length / 2];

		return pack(coefficients, median);
	}

	/**
	 * First separable-DCT pass: transforms each of the 32 rows into its 16
	 * low-frequency coefficients.
	 */
	private static double[][] rowPass(byte[] pixels) {
		double[][] rowDct = new double[MetadataConstants.SAMPLE_SIDE][LOW_FREQUENCY_SIDE];

		for (int row = 0; row < MetadataConstants.SAMPLE_SIDE; row++) {
			for (int frequency = 0; frequency < LOW_FREQUENCY_SIDE; frequency++) {
				double sum = 0;
				for (int column = 0; column < MetadataConstants.SAMPLE_SIDE; column++) {
					sum += (pixels[row * MetadataConstants.SAMPLE_SIDE + column] & 0xFF) * COSINES[frequency][column];
				}
				rowDct[row][frequency] = sum;
			}
		}

		return rowDct;
	}

	/**
	 * Second separable-DCT pass: combines the row coefficients into the 16x16
	 * low-frequency block, flattened into the 256 packed-hash coefficients.
	 */
	private static double[] columnPass(double[][] rowDct) {
		double[] coefficients = new double[HASH_BITS];

		int coefficient = 0;

		for (int vertical = 0; vertical < LOW_FREQUENCY_SIDE; vertical++) {
			for (int horizontal = 0; horizontal < LOW_FREQUENCY_SIDE; horizontal++) {
				double sum = 0;

				for (int row = 0; row < MetadataConstants.SAMPLE_SIDE; row++) {
					sum += rowDct[row][horizontal] * COSINES[vertical][row];
				}

				coefficients[coefficient++] = sum;
			}
		}

		return coefficients;
	}

	/**
	 * Thresholds each coefficient against the AC median into a packed 256-bit hash.
	 */
	private static byte[] pack(double[] coefficients, double median) {
		byte[] hash = new byte[MetadataConstants.HASH_BYTES];

		for (int bit = 0; bit < coefficients.length; bit++) {
			if (coefficients[bit] > median) {
				hash[bit / Byte.SIZE] |= (byte) (1 << (7 - bit % Byte.SIZE));
			}
		}

		return hash;
	}

	/** Hamming distance between two 256-bit pHashes. */
	public static int distance(byte[] first, byte[] second) {
		if (first == null || second == null || first.length != MetadataConstants.HASH_BYTES
				|| second.length != MetadataConstants.HASH_BYTES) {
			throw new IllegalArgumentException("Both pHashes must contain exactly 32 bytes");
		}

		int distance = 0;

		for (int index = 0; index < MetadataConstants.HASH_BYTES; index++) {
			distance += Integer.bitCount((first[index] ^ second[index]) & 0xFF);
		}

		return distance;
	}

	private static double[][] cosineTable() {
		double[][] table = new double[LOW_FREQUENCY_SIDE][MetadataConstants.SAMPLE_SIDE];

		for (int frequency = 0; frequency < LOW_FREQUENCY_SIDE; frequency++) {
			for (int position = 0; position < MetadataConstants.SAMPLE_SIDE; position++) {
				table[frequency][position] = Math
						.cos(Math.PI * (2 * position + 1) * frequency / (2.0 * MetadataConstants.SAMPLE_SIDE));
			}
		}

		return table;
	}
}