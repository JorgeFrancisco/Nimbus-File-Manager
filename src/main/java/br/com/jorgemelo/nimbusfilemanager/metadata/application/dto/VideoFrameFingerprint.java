package br.com.jorgemelo.nimbusfilemanager.metadata.application.dto;

import java.util.Arrays;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataConstants;

/**
 * One sampled video frame: its deterministic relative position
 * ({@code sampleIndex}), the timestamp it was taken from ({@code positionMs},
 * carried as sample metadata, never as the frame's logical identity), the
 * 256-bit pHash and the 32x32 luminance sample for SSIM. Same shape as a
 * photo's fingerprint, one per frame.
 */
public record VideoFrameFingerprint(int sampleIndex, long positionMs, byte[] hash, byte[] luminance) {

	public VideoFrameFingerprint {
		if (sampleIndex < 0) {
			throw new IllegalArgumentException("sampleIndex must not be negative");
		}

		if (hash == null || hash.length != MetadataConstants.HASH_BYTES) {
			throw new IllegalArgumentException("pHash must contain exactly 32 bytes");
		}

		if (luminance == null || luminance.length != MetadataConstants.SAMPLE_BYTES) {
			throw new IllegalArgumentException("Luminance sample must contain exactly 1024 bytes");
		}

		hash = hash.clone();
		luminance = luminance.clone();
	}

	@Override
	public byte[] hash() {
		return hash.clone();
	}

	@Override
	public byte[] luminance() {
		return luminance.clone();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}

		if (o == null || getClass() != o.getClass()) {
			return false;
		}

		VideoFrameFingerprint other = (VideoFrameFingerprint) o;

		return sampleIndex == other.sampleIndex && positionMs == other.positionMs && Arrays.equals(hash, other.hash)
				&& Arrays.equals(luminance, other.luminance);
	}

	@Override
	public int hashCode() {
		int result = 31 * sampleIndex + Long.hashCode(positionMs);

		result = 31 * result + Arrays.hashCode(hash);

		return 31 * result + Arrays.hashCode(luminance);
	}

	@Override
	public String toString() {
		return "VideoFrameFingerprint[sampleIndex=" + sampleIndex + ", positionMs=" + positionMs + "]";
	}
}