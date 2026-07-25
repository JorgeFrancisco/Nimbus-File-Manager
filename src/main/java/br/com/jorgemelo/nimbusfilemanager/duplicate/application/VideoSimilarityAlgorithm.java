package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.nio.file.Path;
import java.util.Set;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoSignature;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoPerceptualFingerprint;

/**
 * The pluggable contract for a video similarity algorithm. It owns the three
 * things that differ between algorithms - how a video's fingerprint is
 * computed, how candidates are bucketed to avoid an O(n^2) all-pairs
 * comparison, and how two signatures are compared/aggregated into a percentage
 * - so the backlog producer and the grouping orchestrator stay closed for
 * modification.
 *
 * <p>
 * A future algorithm (a temporal-alignment matcher, a visual-embedding matcher)
 * is a new {@code @Component} implementing this contract plus a new
 * {@code (kind, algorithm)} identity; nothing in the orchestrator changes and
 * there is no central {@code PHOTO}/{@code VIDEO} switch. The active algorithm
 * is selected by Spring injection, not by a conditional.
 */
public interface VideoSimilarityAlgorithm {

	FingerprintKind kind();

	String algorithm();

	/**
	 * How many frames a fingerprint of this algorithm has - part of its identity,
	 * used to size how many frame rows the grouping loads. Fixed per algorithm.
	 */
	int framesPerFingerprint();

	/** Computes the video's multi-frame fingerprint (used by the backlog). */
	VideoPerceptualFingerprint fingerprint(Path file, Double durationSeconds);

	/**
	 * The set of candidate-bucket keys this video participates in. Two videos are
	 * only compared (the expensive SSIM step) when they share at least one bucket.
	 * Neighboring buckets are included so a small difference in the bucketed signal
	 * never splits a real match.
	 *
	 * <p>
	 * Bucketing is by approximate <b>duration</b> (a recall-safe signal: relative
	 * frame positions make it robust to small duration changes). It is deliberately
	 * NOT an LSH over the frame pHashes: a pHash band index would put a video and
	 * its re-encoded copy - which differ by several hash bits - into different
	 * buckets, defeating the very robustness (re-encode/bitrate/resolution) this
	 * feature exists for. Within a large same-duration bucket the comparison is
	 * therefore O(k^2), but each pair is a cheap popcount pHash pre-filter (SSIM
	 * runs only for pairs that pass it), and {@code k} is bounded by the candidate
	 * cap, so the cost stays controlled without sacrificing recall.
	 */
	Set<Long> candidateBuckets(VideoSignature signature);

	/**
	 * Similarity percentage (0-100) of two videos at the given threshold, or
	 * {@code -1} when they are not a match (incompatible coarse signals, or fewer
	 * than the required number of concordant frames). A returned value {@code >=}
	 * the threshold is a confirmed match; the aggregation is resistant to a few
	 * divergent frames (trimmed mean + a minimum concordant-frame quorum).
	 */
	int similarityPercent(VideoSignature first, VideoSignature second, int minSimilarityPercent);
}