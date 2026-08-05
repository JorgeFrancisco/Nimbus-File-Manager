package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoFrameHash;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoSignature;
import br.com.jorgemelo.nimbusfilemanager.duplicate.calibration.CalibrationVideoSignatures;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.PerceptualHashCodec;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.VideoSimilarityProperties;

/**
 * How the video comparison scales, and where - if anywhere - it stops being
 * affordable.
 *
 * <p>
 * The current library is 5.795 videos and the whole comparison costs seconds.
 * That is a fact about one library at one moment, not a property of the
 * product, and it must not become an architectural premise: the question this
 * answers is what happens at 10k, 20k, 50k and 100k videos, and whether the
 * candidate cap protects a real resource at any of those sizes.
 *
 * <p>
 * <b>Real payload, resampled.</b> The populations are built from the library's
 * own frames: each video takes, for every {@code sampleIndex}, a copy of a real
 * frame drawn from the real frames at that index, plus a real duration and a
 * real display size. Nothing is generated, so the pHash distances are the ones
 * this library actually produces - which matters, because a random 256-bit hash
 * sits four standard deviations from the radius and would make SSIM look free.
 * The copies are deliberate: sharing one array between a hundred thousand
 * videos would measure a cache no real run has.
 *
 * <p>
 * Three scenarios per size. <b>A</b> resamples the real durations, so the
 * duration and aspect gates reject what they reject today. <b>B</b> pulls every
 * duration into one tolerance window - the phone-clip or drone library, where
 * the cheap gate stops filtering. <b>C</b> keeps B and widens the frame radius
 * until every frame reaches SSIM, which is the adverse universe measured rather
 * than imagined.
 *
 * <p>
 * Above a few tens of millions of pairs the pair space is <b>sampled by
 * stride</b> instead of walked: the population is built in full, so the working
 * set and its cache behaviour are the real ones, and the reported time is the
 * measured cost of the sample scaled by the exact pair count. The row says so.
 *
 * <pre>
 * mvnw test -Dtest=VideoSimilarityScaleSpike -Dnimbus.calibration=true
 * </pre>
 */
@EnabledIfSystemProperty(named = "nimbus.calibration", matches = "true")
class VideoSimilarityScaleSpike {

	private static final int[] SIZES = { 5_795, 8_000, 10_000, 20_000, 50_000, 100_000 };

	/** Full 256-bit radius: every frame reaches SSIM, which is scenario C. */
	private static final int EVERY_FRAME = 256;

	/**
	 * How many pairs each scenario walks before it starts striding. The expensive
	 * scenarios sample harder because their per-pair cost is orders of magnitude
	 * apart, not because they matter less.
	 */
	private static final long SAMPLE_CURRENT = 40_000_000L;
	private static final long SAMPLE_SIMILAR = 8_000_000L;
	private static final long SAMPLE_SURVIVING = 800_000L;

	private static final int WARMUP_SIZE = 2_000;

	/** Big enough to stride at scenario A's budget, small enough to also walk. */
	private static final int SAMPLING_CHECK_SIZE = 20_000;

	private static final Path REPORT = Path.of("target", "calibration", "video-scale.txt");

	private final StringBuilder text = new StringBuilder();

	private final VideoSimilarityProperties properties = new VideoSimilarityProperties(null, null, null, null, null);
	private final LuminanceSsimService ssim = new LuminanceSsimService();
	private final FfmpegLanczosFramesPhashAlgorithm algorithm = new FfmpegLanczosFramesPhashAlgorithm(null, ssim,
			properties);

	private final Random random = new Random(20260808L);

	/**
	 * Survivors of the gate-only pass, kept so the measured pass can be checked
	 * against it.
	 */
	private long gateSurvivors;

	@Test
	void measuresHowTheVideoComparisonScales() throws Exception {
		List<VideoSignature> pool = CalibrationVideoSignatures.load();

		Map<Integer, List<VideoFrameHash>> framesByIndex = framesByIndex(pool);

		header(pool);

		warmUp(pool, framesByIndex);

		for (int size : SIZES) {
			sweep(size, pool, framesByIndex);
		}

		samplingCheck(pool, framesByIndex);

		Files.createDirectories(REPORT.getParent());
		Files.writeString(REPORT, text.toString(), StandardCharsets.UTF_8);

		System.out.println(text);
		System.out.println("[video-scale] written to " + REPORT.toAbsolutePath());
	}

	private void header(List<VideoSignature> pool) {
		text.append("Nimbus video similarity - how it scales\n");
		text.append("populations resampled from the real library's own frames, payload copied per video\n");
		text.append(String.format("real pool: %,d videos, radius %d, duration +/- %.1fs, aspect %.2f%n", pool.size(),
				properties.maxFrameHashDistanceOrDefault(), properties.durationToleranceSecondsOrDefault(),
				properties.aspectRatioToleranceOrDefault()));
		text.append("A = real durations | B = every duration inside one tolerance window\n");
		text.append("C = B plus the radius widened so every frame reaches SSIM\n");
		text.append("payload MB is the retained frame payload alone - signatures and buckets sit on top\n");
	}

	/** One small population through the scenarios, so no row is the JIT's. */
	private void warmUp(List<VideoSignature> pool, Map<Integer, List<VideoFrameHash>> framesByIndex) {
		List<List<VideoFrameHash>> frames = frames(WARMUP_SIZE, framesByIndex);

		measure("warmup", signatures(frames, pool, false), properties.maxFrameHashDistanceOrDefault(),
				SAMPLE_SURVIVING, 0);
		measure("warmup", signatures(frames, pool, true), EVERY_FRAME, SAMPLE_SURVIVING, 0);
	}

	private void sweep(int size, List<VideoSignature> pool, Map<Integer, List<VideoFrameHash>> framesByIndex) {
		text.append(String.format("%n=== %,d videos ===%n", size));
		text.append(String.format("%-24s %-16s %-14s %-15s %-14s %-11s %-10s %-10s %s%n", "scenario", "pairs",
				"through gates", "frame compares", "SSIM calls", "time", "ns/pair", "payload MB", "dominant"));

		long baseline = settle();

		List<List<VideoFrameHash>> frames = frames(size, framesByIndex);

		double retained = megabytes(settle() - baseline);

		int radius = properties.maxFrameHashDistanceOrDefault();

		text.append(measure("A real durations", signatures(frames, pool, false), radius, SAMPLE_CURRENT, retained));
		text.append(measure("B similar durations", signatures(frames, pool, true), radius, SAMPLE_SIMILAR, retained));
		text.append(measure("C every frame to SSIM", signatures(frames, pool, true), EVERY_FRAME, SAMPLE_SURVIVING,
				retained));
	}

	/**
	 * How much of the growth in ns/pair is the population and how much is the
	 * stride.
	 *
	 * <p>
	 * Striding is what makes the large sizes measurable, and it is not free: a real
	 * run walks the pair space in order, while a strided one jumps, so the sampled
	 * rows pay cache misses a full run would not. One population is therefore
	 * measured both ways. A row pair that agrees means the stride is honest and the
	 * degradation above is the working set; a walked row that is faster means the
	 * large sizes are quoted pessimistically, and by how much.
	 */
	private void samplingCheck(List<VideoSignature> pool, Map<Integer, List<VideoFrameHash>> framesByIndex) {
		text.append(String.format("%n=== sampling check: %,d videos, scenario A, walked vs strided ===%n",
				SAMPLING_CHECK_SIZE));
		text.append(String.format("%-24s %-16s %-14s %-15s %-14s %-11s %-10s %-10s %s%n", "scenario", "pairs",
				"through gates", "frame compares", "SSIM calls", "time", "ns/pair", "payload MB", "dominant"));

		List<VideoSignature> videos = signatures(frames(SAMPLING_CHECK_SIZE, framesByIndex), pool, false);

		int radius = properties.maxFrameHashDistanceOrDefault();

		text.append(measure("A walked in full", videos, radius, Long.MAX_VALUE, 0));
		text.append(measure("A strided", videos, radius, SAMPLE_CURRENT, 0));
	}

	/**
	 * One population through the production comparison: the bucket gate, then the
	 * duration and aspect gates, then frame-for-frame distance, then SSIM for the
	 * frames the radius let through. Returns the report row rather than writing it,
	 * so the warm-up can run the identical code and throw the row away.
	 */
	private String measure(String label, List<VideoSignature> videos, int radius, long pairSample, double retained) {
		Map<UUID, Set<Long>> buckets = buckets(videos);

		long pairs = (long) videos.size() * (videos.size() - 1) / 2;
		int stride = (int) Math.max(1, pairs / pairSample);

		long gateNanos = gates(videos, buckets, stride);

		long sampled = 0;
		long gated = 0;
		long compared = 0;
		long calls = 0;
		long ssimNanos = 0;

		long started = System.nanoTime();

		for (int first = 0; first < videos.size(); first++) {
			VideoSignature left = videos.get(first);

			for (int second = first + 1; second < videos.size(); second += stride) {
				VideoSignature right = videos.get(second);

				sampled++;

				if (Collections.disjoint(buckets.get(left.id()), buckets.get(right.id()))
						|| !durationCompatible(left, right) || !aspectRatioCompatible(left, right)) {
					continue;
				}

				gated++;

				Map<Integer, VideoFrameHash> secondByIndex = new HashMap<>();

				for (VideoFrameHash frame : right.frames()) {
					secondByIndex.put(frame.sampleIndex(), frame);
				}

				for (VideoFrameHash frame : left.frames()) {
					VideoFrameHash other = secondByIndex.get(frame.sampleIndex());

					if (other == null) {
						continue;
					}

					compared++;

					if (PerceptualHashCodec.distance(frame.phash(), other.phash()) > radius) {
						continue;
					}

					calls++;

					long callStarted = System.nanoTime();
					ssim.similarityPercent(frame.luminance(), other.luminance());
					ssimNanos += System.nanoTime() - callStarted;
				}
			}
		}

		long nanos = System.nanoTime() - started;

		double scale = sampled == 0 ? 0 : (double) pairs / sampled;
		double scaled = nanos * scale;

		return String.format("%-24s %-16d %-14d %-15d %-14d %-11s %-10.2f %-10.0f %s%s%s%n", label, pairs,
				Math.round(gated * scale), Math.round(compared * scale), Math.round(calls * scale),
				formatSeconds((long) scaled), scaled / pairs, retained,
				dominant(percent(ssimNanos, nanos), percent(gateNanos, nanos)),
				scale > 1 ? String.format("  (1/%.0f sampled)", scale) : "",
				gateSurvivors == gated ? "" : "  !gate mismatch");
	}

	/**
	 * The same walk with the frame work removed, so the row can say whether the
	 * cheap gates or the expensive stage is what the run spends its time on. The
	 * survivor count is kept rather than discarded: it has to equal the measured
	 * pass's, and a loop whose result nothing reads is a loop the JIT may drop.
	 */
	private long gates(List<VideoSignature> videos, Map<UUID, Set<Long>> buckets, int stride) {
		long survivors = 0;

		long started = System.nanoTime();

		for (int first = 0; first < videos.size(); first++) {
			VideoSignature left = videos.get(first);

			for (int second = first + 1; second < videos.size(); second += stride) {
				VideoSignature right = videos.get(second);

				if (!Collections.disjoint(buckets.get(left.id()), buckets.get(right.id()))
						&& durationCompatible(left, right) && aspectRatioCompatible(left, right)) {
					survivors++;
				}
			}
		}

		long nanos = System.nanoTime() - started;

		gateSurvivors = survivors;

		return nanos;
	}

	private String dominant(double ssimShare, double gateShare) {
		if (ssimShare >= 50) {
			return String.format("SSIM %.0f%%", ssimShare);
		}

		if (gateShare >= 50) {
			return String.format("gates %.0f%%", gateShare);
		}

		return String.format("frame hashing %.0f%%", Math.max(0, 100 - ssimShare - gateShare));
	}

	private Map<Integer, List<VideoFrameHash>> framesByIndex(List<VideoSignature> pool) {
		Map<Integer, List<VideoFrameHash>> byIndex = new HashMap<>();

		for (VideoSignature video : pool) {
			for (VideoFrameHash frame : video.frames()) {
				byIndex.computeIfAbsent(frame.sampleIndex(), _ -> new ArrayList<>()).add(frame);
			}
		}

		return byIndex;
	}

	/**
	 * The frame payload of one population: for every sample index, a copy of a real
	 * frame drawn from the real frames at that index.
	 */
	private List<List<VideoFrameHash>> frames(int size, Map<Integer, List<VideoFrameHash>> framesByIndex) {
		List<List<VideoFrameHash>> population = new ArrayList<>(size);

		for (int video = 0; video < size; video++) {
			List<VideoFrameHash> frames = new ArrayList<>(FfmpegLanczosFramesPhashAlgorithm.FRAME_SAMPLES);

			for (int index = 0; index < FfmpegLanczosFramesPhashAlgorithm.FRAME_SAMPLES; index++) {
				List<VideoFrameHash> candidates = framesByIndex.get(index);

				VideoFrameHash source = candidates.get(random.nextInt(candidates.size()));

				frames.add(new VideoFrameHash(index, source.phash().clone(), source.luminance().clone()));
			}

			population.add(frames);
		}

		return population;
	}

	/**
	 * The signatures over an already-built frame payload. Duration and display size
	 * come from a real video, so scenario A reproduces the gates the library has
	 * today; the narrow variant keeps the real display size and pulls every
	 * duration into a single tolerance window.
	 */
	private List<VideoSignature> signatures(List<List<VideoFrameHash>> frames, List<VideoSignature> pool,
			boolean similarDurations) {
		double window = properties.durationToleranceSecondsOrDefault();

		List<VideoSignature> videos = new ArrayList<>(frames.size());

		for (int index = 0; index < frames.size(); index++) {
			VideoSignature source = pool.get(random.nextInt(pool.size()));

			Double duration = similarDurations ? 30.0 + random.nextDouble() * window : source.durationSeconds();

			videos.add(new VideoSignature(new UUID(1, index), frames.get(index), duration, source.width(),
					source.height()));
		}

		return videos;
	}

	private Map<UUID, Set<Long>> buckets(List<VideoSignature> videos) {
		Map<UUID, Set<Long>> buckets = new HashMap<>();

		for (VideoSignature video : videos) {
			buckets.put(video.id(), algorithm.candidateBuckets(video));
		}

		return buckets;
	}

	private boolean durationCompatible(VideoSignature first, VideoSignature second) {
		Double firstDuration = first.durationSeconds();
		Double secondDuration = second.durationSeconds();

		if (firstDuration == null || secondDuration == null) {
			return true;
		}

		return Math.abs(firstDuration - secondDuration) <= properties.durationToleranceSecondsOrDefault();
	}

	private boolean aspectRatioCompatible(VideoSignature first, VideoSignature second) {
		Double firstRatio = aspectRatio(first);
		Double secondRatio = aspectRatio(second);

		if (firstRatio == null || secondRatio == null) {
			return true;
		}

		double relativeDifference = Math.abs(firstRatio - secondRatio) / Math.max(firstRatio, secondRatio);

		return relativeDifference <= properties.aspectRatioToleranceOrDefault();
	}

	private Double aspectRatio(VideoSignature signature) {
		Integer width = signature.width();
		Integer height = signature.height();

		if (width == null || height == null || width <= 0 || height <= 0) {
			return null;
		}

		return width.doubleValue() / height;
	}

	private long settle() {
		System.gc();

		return usedHeap();
	}

	private double percent(long part, long whole) {
		return whole == 0 ? 0 : 100.0 * part / whole;
	}

	private String formatSeconds(long nanos) {
		return String.format("%.2fs", nanos / 1_000_000_000.0);
	}

	private double megabytes(long bytes) {
		return bytes / (1024.0 * 1024.0);
	}

	private long usedHeap() {
		Runtime runtime = Runtime.getRuntime();

		return runtime.totalMemory() - runtime.freeMemory();
	}
}