package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoFrameHash;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoSignature;

/**
 * Video signatures built to order, so a test can decide what the comparison
 * will find instead of hoping a random population contains it.
 *
 * <p>
 * A frame here is a <b>group</b> and a <b>level</b>. The group picks the pHash:
 * two frames of the same group are at distance zero, two frames of different
 * groups are far beyond any usable radius, so the group alone decides whether
 * the cheap filter lets the frame through. The level then picks the luminance:
 * level {@code n} is the group's sample with its first {@code n} window bands
 * overwritten, so two frames of the same group differ in exactly
 * {@code |a - b|} bands of the sixteen SSIM windows and their score falls in
 * steps of roughly a quarter. Same distance apart means the same score, which is
 * how a tie is arranged, and a different distance means a different one.
 *
 * <p>
 * The hash is stored rather than derived from the sample, which production never
 * does. That is the point: the two filters are steered independently here, so a
 * test can put a pair inside the radius and still fail the quorum, or outside it
 * while the samples are identical - the combinations the real data offers only
 * by accident.
 */
final class SyntheticVideoSignatures {

	static final int LEVELS = 5;

	static final double DEFAULT_DURATION_SECONDS = 30.0;
	static final int DEFAULT_WIDTH = 1920;
	static final int DEFAULT_HEIGHT = 1080;

	private static final int SAMPLE_BYTES = 1024;
	private static final int HASH_BYTES = 32;

	/**
	 * Bytes one level overwrites: eight image rows, which is exactly one band of
	 * four of the sixteen 8x8 SSIM windows.
	 */
	private static final int BAND_BYTES = 256;

	private SyntheticVideoSignatures() {
	}

	/** One frame: the group that decides its hash, the level that spoils it. */
	static int frame(int group, int level) {
		return group * LEVELS + level;
	}

	/** A video of the default duration and shape, for tests that ignore both. */
	static VideoSignature video(long id, int... frames) {
		return video(id, DEFAULT_DURATION_SECONDS, DEFAULT_WIDTH, DEFAULT_HEIGHT, frames);
	}

	static VideoSignature video(long id, Double durationSeconds, Integer width, Integer height, int... frames) {
		List<VideoFrameHash> hashes = new ArrayList<>(frames.length);

		for (int index = 0; index < frames.length; index++) {
			hashes.add(new VideoFrameHash(index, hash(frames[index] / LEVELS),
					sample(frames[index] / LEVELS, frames[index] % LEVELS)));
		}

		return new VideoSignature(new UUID(0, id), List.copyOf(hashes), durationSeconds, width, height);
	}

	/**
	 * The same video with only some of its frames, keeping each one's original
	 * {@code sampleIndex} - which is what a video whose fingerprint is missing a
	 * frame looks like, and the only way to align two videos on a subset.
	 */
	static VideoSignature withFrames(VideoSignature video, int... sampleIndexes) {
		List<VideoFrameHash> kept = new ArrayList<>(sampleIndexes.length);

		for (int sampleIndex : sampleIndexes) {
			kept.add(video.frames().stream().filter(frame -> frame.sampleIndex() == sampleIndex).findFirst()
					.orElseThrow(() -> new IllegalArgumentException("no frame at sample index " + sampleIndex)));
		}

		return new VideoSignature(video.id(), List.copyOf(kept), video.durationSeconds(), video.width(),
				video.height());
	}

	/**
	 * The pHash of a group. Drawn from a stream of its own so that two groups sit
	 * about half the hash apart, which is where two unrelated images sit - the
	 * {@code hashesOfDifferentGroupsAreOutsideAnyUsableRadius} test is what keeps
	 * that from being an assumption.
	 */
	static byte[] hash(int group) {
		byte[] hash = new byte[HASH_BYTES];

		new Random(group * 7919L + 13).nextBytes(hash);

		return hash;
	}

	/**
	 * The luminance sample of a group at a level: the group's own content, with the
	 * first {@code level} bands overwritten by unrelated content. Both halves are
	 * deterministic and neither depends on the level, so two levels agree
	 * everywhere except in the bands between them.
	 */
	static byte[] sample(int group, int level) {
		byte[] base = new byte[SAMPLE_BYTES];
		byte[] overwritten = new byte[SAMPLE_BYTES];

		new Random(group * 104_729L + 7).nextBytes(base);
		new Random(group * 104_729L + 8191).nextBytes(overwritten);

		System.arraycopy(overwritten, 0, base, 0, Math.min(level * BAND_BYTES, SAMPLE_BYTES));

		return base;
	}
}