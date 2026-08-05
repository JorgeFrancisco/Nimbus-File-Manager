package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoFrameHash;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoSignature;
import br.com.jorgemelo.nimbusfilemanager.duplicate.calibration.CalibrationVideoSignatures;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.PerceptualHashCodec;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.VideoSimilarityProperties;

/**
 * What an arrival costs against the real library, and where it stops being
 * cheaper than a rebuild.
 *
 * <p>
 * Read-only against the running application's database and it writes nothing
 * anywhere: the arrival is simulated <em>logically</em> - the last {@code N}
 * videos by catalog id stand in for the newcomers and the rest for the covered
 * set - so no coverage row is invented and the library is left exactly as it
 * was. The selection is by descending catalog id because that is what an
 * arrival really is: the newest files are the ones nobody has compared.
 *
 * <p>
 * <b>What this measures and what it does not.</b> It measures the comparison,
 * which is the part that scales with the library and the part the ADD-versus-
 * rebuild choice turns on: the cheap gates over every pair an arrival creates,
 * the frames the survivors force to be read, the pHash distances and the SSIM.
 * It does not measure the writer, the regroup read, the BUILDING insert or the
 * publication - those are proportional to the <em>relations</em>, which an
 * arrival produces few of, and they are the same shared code a photo run
 * already exercises. A row of this report is therefore a lower bound on a real
 * ADD and an honest upper bound on what an arrival can save.
 *
 * <p>
 * The production algorithm decides every verdict here; nothing is
 * reimplemented. What the spike adds is counting.
 *
 * <pre>
 * mvnw test -Dtest=VideoSimilarityAddCostSpike -Dnimbus.calibration=true
 * </pre>
 */
@EnabledIfSystemProperty(named = "nimbus.calibration", matches = "true")
class VideoSimilarityAddCostSpike {

	private static final int[] ARRIVALS = { 1, 10, 100, 1_000 };

	private static final Path REPORT = Path.of("target", "calibration", "video-add-cost.txt");

	private final VideoSimilarityProperties properties = new VideoSimilarityProperties(null, null, null, null, null);
	private final FfmpegLanczosFramesPhashAlgorithm algorithm = new FfmpegLanczosFramesPhashAlgorithm(null,
			new LuminanceSsimService(), properties);

	private final StringBuilder text = new StringBuilder();

	@Test
	void measuresWhatAnArrivalCostsAgainstTheRealLibrary() throws Exception {
		List<VideoSignature> pool = CalibrationVideoSignatures.load();

		text.append("Nimbus video similarity - what an arrival costs\n");
		text.append(String.format("library: %,d videos, radius %d, duration +/- %.1fs, aspect %.2f%n", pool.size(),
				properties.maxFrameHashDistanceOrDefault(), properties.durationToleranceSecondsOrDefault(),
				properties.aspectRatioToleranceOrDefault()));
		text.append("the newest N by catalog id stand in for the newcomers; the rest are the covered set\n");
		text.append("comparison only - writer, regroup read, BUILDING and publish are not in these numbers\n");

		text.append(String.format("%n%-8s %-9s %-14s %-12s %-14s %-11s %-13s %-10s %-10s %-9s %-9s%n", "N", "C",
				"pairs", "past gates", "frame compares", "SSIM calls", "videos read", "gate ms", "compare ms",
				"total ms", "approved"));

		for (int arrivals : ARRIVALS) {
			arrival(pool, Math.min(arrivals, pool.size()));
		}

		full(pool);

		Files.createDirectories(REPORT.getParent());
		Files.writeString(REPORT, text.toString(), StandardCharsets.UTF_8);

		System.out.println(text);
		System.out.println("[video-add-cost] written to " + REPORT.toAbsolutePath());
	}

	/**
	 * One arrival: every pair a newcomer takes part in, through the cheap gates
	 * first and the frames only for what survives - which is the two-phase shape
	 * {@code VideoArrivalRelationBuilder} has, measured over the real signatures.
	 */
	private void arrival(List<VideoSignature> pool, int arrivals) {
		int count = pool.size();
		int firstNewcomer = count - arrivals;

		long pairs = 0;
		long gated = 0;

		List<long[]> surviving = new ArrayList<>();

		long gateStarted = System.nanoTime();

		for (int left = firstNewcomer; left < count; left++) {
			for (int right = 0; right < count; right++) {
				// A pair of two newcomers is offered twice; the second spelling is dropped,
				// which is what makes this N x C + N x N and never C x C.
				if (right == left || (right >= firstNewcomer && right < left)) {
					continue;
				}

				pairs++;

				if (passesTheCheapGates(pool.get(left), pool.get(right))) {
					gated++;

					surviving.add(new long[] { left, right });
				}
			}
		}

		long gateNanos = System.nanoTime() - gateStarted;

		compare(pool, surviving, arrivals, count - arrivals, pairs, gated, gateNanos);
	}

	/** The expensive half, over the pairs the gates admitted. */
	private void compare(List<VideoSignature> pool, List<long[]> surviving, int arrivals, int covered, long pairs,
			long gated, long gateNanos) {
		int minimum = 70;

		long frameComparisons = 0;
		long ssimCalls = 0;
		long approved = 0;

		Set<Integer> read = new HashSet<>();

		long started = System.nanoTime();

		for (long[] pair : surviving) {
			VideoSignature first = pool.get((int) pair[0]);
			VideoSignature second = pool.get((int) pair[1]);

			read.add((int) pair[0]);
			read.add((int) pair[1]);

			frameComparisons += Math.min(first.frames().size(), second.frames().size());
			ssimCalls += inRadius(first, second);

			if (algorithm.similarityPercent(first, second, minimum) >= minimum) {
				approved++;
			}
		}

		long compareNanos = System.nanoTime() - started;

		text.append(String.format("%-8d %-9d %-14d %-12d %-14d %-11d %-13d %-10.1f %-10.1f %-9.1f %-9d%n", arrivals,
				covered, pairs, gated, frameComparisons, ssimCalls, read.size(), millis(gateNanos),
				millis(compareNanos), millis(gateNanos + compareNanos), approved));
	}

	/** The same comparison over every pair, which is what a rebuild does. */
	private void full(List<VideoSignature> pool) {
		int count = pool.size();

		long pairs = 0;
		long gated = 0;

		List<long[]> surviving = new ArrayList<>();

		long gateStarted = System.nanoTime();

		for (int left = 0; left < count; left++) {
			for (int right = left + 1; right < count; right++) {
				pairs++;

				if (passesTheCheapGates(pool.get(left), pool.get(right))) {
					gated++;

					surviving.add(new long[] { left, right });
				}
			}
		}

		long gateNanos = System.nanoTime() - gateStarted;

		text.append(String.format("%nrebuild over the same library:%n"));

		compare(pool, surviving, count, 0, pairs, gated, gateNanos);
	}

	/**
	 * The bucket gate and the duration/aspect gates, which is exactly what an
	 * arrival applies before it decides a video's frames are worth reading.
	 */
	private boolean passesTheCheapGates(VideoSignature first, VideoSignature second) {
		return !Collections.disjoint(algorithm.candidateBuckets(first), algorithm.candidateBuckets(second))
				&& algorithm.gatesAllow(first, second);
	}

	/** How many aligned frames the pHash radius admits, which is the SSIM count. */
	private long inRadius(VideoSignature first, VideoSignature second) {
		int radius = properties.maxFrameHashDistanceOrDefault();

		long inRadius = 0;

		for (VideoFrameHash frame : first.frames()) {
			for (VideoFrameHash other : second.frames()) {
				if (frame.sampleIndex() == other.sampleIndex()
						&& PerceptualHashCodec.distance(frame.phash(), other.phash()) <= radius) {
					inRadius++;
				}
			}
		}

		return inRadius;
	}

	private double millis(long nanos) {
		return nanos / 1_000_000.0;
	}
}