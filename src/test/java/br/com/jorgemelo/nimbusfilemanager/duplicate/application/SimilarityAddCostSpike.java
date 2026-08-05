package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import br.com.jorgemelo.nimbusfilemanager.duplicate.calibration.CalibrationHashes;
import br.com.jorgemelo.nimbusfilemanager.duplicate.calibration.CalibrationSamples;
import br.com.jorgemelo.nimbusfilemanager.duplicate.calibration.LoadedHashes;

/**
 * What an arrival costs on the real library, at four batch sizes, against what a
 * full rebuild costs on the same machine in the same run.
 *
 * <p>
 * The comparison is the point, and so is running both in one process: absolute
 * seconds vary with what else the machine is doing, and a ratio measured
 * minutes apart on a contended laptop has been misread as a regression here
 * before. Both numbers come out of the same JVM, over the same data, moments
 * apart.
 *
 * <p>
 * <b>Read-only, against the running application's database.</b> Nothing is
 * written and nothing is queued: the arrivals are simulated by treating the last
 * ids as not yet covered, which is exactly the set an arrival would find. The
 * phases that write - persisting relations and coverage, materialising the
 * BUILDING grouping, promoting it - are not measured here, because measuring
 * them would mean writing to a library that took years to build. They are
 * measured against Testcontainers elsewhere and are the same phases for both
 * routes.
 *
 * <pre>
 * mvnw test -Dtest=SimilarityAddCostSpike -Dnimbus.calibration=true
 * </pre>
 */
@EnabledIfSystemProperty(named = "nimbus.calibration", matches = "true")
class SimilarityAddCostSpike {

	private static final String ALGORITHM = "FFMPEG_LANCZOS_PHASH_256_V1";
	private static final int RADIUS = 96;
	private static final int MINIMUM = 95;

	private static final int[] BATCHES = { 1, 10, 100, 1000 };

	private static final Path REPORT = Path.of("target", "calibration", "add-cost.txt");

	private static final SimilarityProgressCallback SILENT = (_, _) -> {
	};

	@Test
	void measuresWhatAnArrivalCostsAgainstWhatARebuildCosts() throws Exception {
		StringBuilder text = new StringBuilder();

		text.append("Nimbus similarity - the cost of an arrival, measured\n");
		text.append("radius ").append(RADIUS).append(", SSIM >= ").append(MINIMUM).append('\n');

		long loadStarted = System.nanoTime();
		LoadedHashes hashes = CalibrationHashes.load(ALGORITHM);
		long loadNanos = System.nanoTime() - loadStarted;

		int library = hashes.count();

		text.append(String.format("library: %,d photos, hashes loaded in %.2fs (%.1f MB)%n", library,
				seconds(loadNanos), library * 32.0 / (1024 * 1024)));

		long rebuildNanos = rebuild(text, hashes, library);

		text.append(String.format("%n%-8s %-12s %-10s %-11s %-9s %-9s %-9s %-9s %-8s%n", "arrivals", "comparisons",
				"hamming", "candidates", "samples", "sampleIO", "ssim", "compare", "vs rebuild"));

		for (int arrivals : BATCHES) {
			measure(text, hashes, library, arrivals, rebuildNanos);
		}

		crossover(text, library, rebuildNanos);

		Files.createDirectories(REPORT.getParent());
		Files.writeString(REPORT, text.toString(), StandardCharsets.UTF_8);

		System.out.println(text);
		System.out.println("[add-cost] written to " + REPORT.toAbsolutePath());
	}

	/**
	 * The rebuild's comparison phase, over the same hashes and in the same run.
	 * Its distance scan is what an arrival's has to be compared against; the SSIM
	 * that follows is the same code for both and is measured per batch below.
	 */
	private long rebuild(StringBuilder text, LoadedHashes hashes, int library) {
		long started = System.nanoTime();

		long[] pairs = PhotoRelationBuilder.withinRadius(hashes.packed(), library, RADIUS, SILENT);

		long nanos = System.nanoTime() - started;

		long comparisons = (long) library * (library - 1) / 2;

		text.append(String.format("%nFULL REBUILD distance scan: %.2fs for %,d comparisons (%.2f ns each),"
				+ " %,d candidates%n", seconds(nanos), comparisons, (double) nanos / comparisons, pairs.length));

		return nanos;
	}

	private void measure(StringBuilder text, LoadedHashes hashes, int library, int arrivals, long rebuildNanos)
			throws Exception {
		boolean[] newcomer = new boolean[library];

		// The highest catalog ids, which is what an arrival is: the rows are ordered
		// by id, so the last of them are the newest.
		for (int index = library - arrivals; index < library; index++) {
			newcomer[index] = true;
		}

		long scanStarted = System.nanoTime();
		long[] pairs = ArrivingPairs.withinRadius(hashes.packed(), newcomer, library, RADIUS, arrivals, SILENT);
		long scanNanos = System.nanoTime() - scanStarted;

		long[] wanted = participants(hashes.ids(), pairs);

		long sampleStarted = System.nanoTime();
		Map<Long, byte[]> samples = wanted.length == 0 ? Map.of() : CalibrationSamples.loadOnly(ALGORITHM, wanted);
		long sampleNanos = System.nanoTime() - sampleStarted;

		byte[][] luminance = new byte[library][];

		for (long pair : pairs) {
			luminance[(int) (pair >>> 32)] = samples.get(hashes.ids()[(int) (pair >>> 32)]);
			luminance[(int) pair] = samples.get(hashes.ids()[(int) pair]);
		}

		long ssimStarted = System.nanoTime();
		int approved = new PhotoRelationBuilder(new LuminanceSsimService(), RADIUS)
				.approve(luminance, pairs, MINIMUM, arrivals, SILENT).count();
		long ssimNanos = System.nanoTime() - ssimStarted;

		long comparisons = (long) arrivals * (library - arrivals) + (long) arrivals * (arrivals - 1) / 2;

		long compareNanos = scanNanos + sampleNanos + ssimNanos;

		text.append(String.format("%-8d %-12d %-10s %-11d %-9d %-9s %-9s %-9s %.1fx%n", arrivals, comparisons,
				millis(scanNanos), pairs.length, samples.size(), millis(sampleNanos), millis(ssimNanos),
				millis(compareNanos), (double) rebuildNanos / compareNanos));

		text.append(String.format("         approved %d relation(s), samples read %.2f MB of the %.0f MB a rebuild"
				+ " would load%n", approved, samples.size() * 1024.0 / (1024 * 1024),
				library * 1024.0 / (1024 * 1024)));
	}

	/**
	 * Where the two routes meet, derived from what was just measured rather than
	 * guessed: an arrival compares {@code N x (C - N)} pairs and a rebuild
	 * {@code C x (C - 1) / 2}, at the same cost per comparison, so the sizes at
	 * which the two are equal follow from the counts alone.
	 */
	private void crossover(StringBuilder text, int library, long rebuildNanos) {
		long rebuildComparisons = (long) library * (library - 1) / 2;

		long even = rebuildComparisons / library;

		text.append(String.format("%nCROSSOVER%n"));
		text.append(String.format("  a rebuild compares %,d pairs; an arrival of N compares about N x %,d%n",
				rebuildComparisons, library));
		text.append(String.format("  so the comparison phases are equal at N = %,d, which is half the library%n",
				even));
		text.append(String.format("  measured rebuild scan: %.2fs - an arrival of %,d would cost about the same%n",
				seconds(rebuildNanos), even));
		text.append("  below that the arrival is cheaper by the ratio in the table, and it also skips the\n");
		text.append("  126 MB sample load a rebuild pays before comparing anything\n");
	}

	private long[] participants(long[] ids, long[] pairs) {
		boolean[] needed = new boolean[ids.length];

		for (long pair : pairs) {
			needed[(int) (pair >>> 32)] = true;
			needed[(int) pair] = true;
		}

		int size = 0;

		for (boolean one : needed) {
			if (one) {
				size++;
			}
		}

		long[] wanted = new long[size];
		int position = 0;

		for (int index = 0; index < needed.length; index++) {
			if (needed[index]) {
				wanted[position++] = ids[index];
			}
		}

		return wanted;
	}

	private String millis(long nanos) {
		return String.format("%.0fms", nanos / 1_000_000.0);
	}

	private double seconds(long nanos) {
		return nanos / 1_000_000_000.0;
	}
}