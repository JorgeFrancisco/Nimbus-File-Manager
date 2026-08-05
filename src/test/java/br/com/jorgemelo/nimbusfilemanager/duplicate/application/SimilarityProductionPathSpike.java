package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import br.com.jorgemelo.nimbusfilemanager.duplicate.calibration.CalibrationHashes;
import br.com.jorgemelo.nimbusfilemanager.duplicate.calibration.CalibrationSamples;
import br.com.jorgemelo.nimbusfilemanager.duplicate.calibration.LoadedHashes;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoHashRawResponse;

/**
 * The production path itself, timed and weighed, with nothing beside it.
 *
 * <p>
 * Earlier runs reported a peak of 529 MB, but they were benchmarks: they kept a
 * boxed map of samples, an aligned copy of the same samples, and two pair lists
 * alive at once so that variants could be compared. None of that exists in the
 * real path, and a memory figure that includes it would be a figure about the
 * measurement. This builds the inputs the way production has them, calls
 * {@link PhotoRelationBuilder} and {@link SimilarityRelationGrouper}, and takes
 * a reading between stages.
 *
 * <pre>
 * mvnw test -Dtest=SimilarityProductionPathSpike -Dnimbus.calibration=true
 * </pre>
 */
@EnabledIfSystemProperty(named = "nimbus.calibration", matches = "true")
class SimilarityProductionPathSpike {

	private static final String ALGORITHM = "FFMPEG_LANCZOS_PHASH_256_V1";
	private static final int RADIUS = 96;
	private static final int MINIMUM = 95;

	private static final Path REPORT = Path.of("target", "calibration", "production-path.txt");

	@Test
	void runsAndWeighsTheProductionPath() throws Exception {
		StringBuilder text = new StringBuilder();

		text.append("Nimbus similarity - the production path, measured\n");
		text.append("radius ").append(RADIUS).append(", SSIM >= ").append(MINIMUM)
				.append(", distance scan sequential\n");
		text.append("cores: ").append(Runtime.getRuntime().availableProcessors()).append('\n');

		long baseline = settle();

		text.append(String.format("%nheap before the analysis: %.0f MB%n", megabytes(baseline)));

		long loadStarted = System.nanoTime();
		List<PhotoHashRawResponse> candidates = load();
		long loadNanos = System.nanoTime() - loadStarted;

		long afterLoad = usedHeap();

		text.append(String.format("load (hashes + samples as production rows): %.2fs, heap %.0f MB%n",
				seconds(loadNanos), megabytes(afterLoad)));

		long scanStarted = System.nanoTime();
		long[] pairs = PhotoRelationBuilder.withinRadius(PhotoRelationBuilder.pack(candidates), candidates.size(),
				RADIUS, (_, _) -> {
				});
		long scanNanos = System.nanoTime() - scanStarted;

		long afterScan = usedHeap();

		text.append(String.format("hamming scan: %.2fs, heap %.0f MB, %,d candidates (%.1f MB)%n",
				seconds(scanNanos), megabytes(afterScan), pairs.length, pairs.length * 8.0 / (1024 * 1024)));

		long approveStarted = System.nanoTime();
		ApprovedRelations relations = new PhotoRelationBuilder(new LuminanceSsimService(), RADIUS)
				.approve(candidates, pairs, MINIMUM, (_, _) -> {
				}).relations();
		long approveNanos = System.nanoTime() - approveStarted;

		long afterApprove = usedHeap();

		text.append(String.format("ssim + relations: %.2fs, heap %.0f MB, structure %.1f MB%n", seconds(approveNanos),
				megabytes(afterApprove), relations.bytes() / (1024.0 * 1024.0)));

		long clusterStarted = System.nanoTime();
		List<List<Integer>> clusters = SimilarityRelationGrouper.cluster(candidates.size(), relations, (_, _) -> {
		});
		long clusterNanos = System.nanoTime() - clusterStarted;

		long afterCluster = usedHeap();

		text.append(String.format("clustering: %.2fs, heap %.0f MB%n", seconds(clusterNanos), megabytes(afterCluster)));

		// The highest of the readings, not the last one. The last is taken after the
		// collector has had every chance to run, and reporting it as the peak would
		// understate the load by the largest thing the analysis ever held - which on
		// this path is the samples, freed before the end.
		long peak = Math.max(Math.max(afterLoad, afterScan), Math.max(afterApprove, afterCluster));

		total(text, loadNanos, scanNanos, approveNanos, clusterNanos);
		memory(text, baseline, peak);
		result(text, clusters, pairs.length, relations);

		Files.createDirectories(REPORT.getParent());
		Files.writeString(REPORT, text.toString(), StandardCharsets.UTF_8);

		System.out.println(text);
		System.out.println("[production] written to " + REPORT.toAbsolutePath());
	}

	private void total(StringBuilder text, long load, long scan, long approve, long cluster) {
		text.append(String.format("%nTOTAL: %.2fs (load %.2f + scan %.2f + ssim %.2f + clustering %.2f)%n",
				seconds(load + scan + approve + cluster), seconds(load), seconds(scan), seconds(approve),
				seconds(cluster)));
	}

	/**
	 * Measured against the worker's real budget, which is 4g - the default in
	 * {@code WorkerProperties} that {@code ProcessBuilderWorkerLauncher} passes as
	 * {@code -Xmx} when it starts the second JVM.
	 */
	private void memory(StringBuilder text, long baseline, long peak) {
		long worker = 4L * 1024 * 1024 * 1024;

		text.append(String.format("%npeak heap: %.0f MB, of which the analysis added %.0f MB%n", megabytes(peak),
				megabytes(peak - baseline)));
		text.append(String.format("worker budget: 4096 MB, margin left %.0f MB (%.1f%%)%n",
				megabytes(worker - peak), 100.0 * (worker - peak) / worker));
	}

	private void result(StringBuilder text, List<List<Integer>> clusters, int candidatePairs,
			ApprovedRelations relations) {
		int[] sizes = clusters.stream().mapToInt(List::size).sorted().toArray();

		text.append(String.format("%ncandidate pairs: %,d%n", candidatePairs));
		text.append("groups: ").append(clusters.size()).append(" | files grouped: ").append(Arrays.stream(sizes).sum())
				.append(" | largest: ").append(sizes.length == 0 ? 0 : sizes[sizes.length - 1]).append('\n');
		text.append("relation structure: ").append(String.format("%.1f MB%n", relations.bytes() / (1024.0 * 1024.0)));
	}

	/**
	 * Production reads rows carrying both the hash and the sample, so the spike
	 * builds the same shape - one object per photo, no second copy of anything.
	 */
	private List<PhotoHashRawResponse> load() throws Exception {
		LoadedHashes hashes = CalibrationHashes.load(ALGORITHM);
		Map<Long, byte[]> samples = CalibrationSamples.loadAll(ALGORITHM);

		List<PhotoHashRawResponse> rows = new ArrayList<>(hashes.count());

		for (int index = 0; index < hashes.count(); index++) {
			rows.add(new PhotoHashRawResponse(hashes.ids()[index], new UUID(0, index),
					unpack(hashes.packed(), index), samples.get(hashes.ids()[index]), "photo" + index, "jpg", 1L,
					"C:/x", "C:/x", null));
		}

		return rows;
	}

	private byte[] unpack(long[] packed, int index) {
		byte[] hash = new byte[32];

		for (int word = 0; word < 4; word++) {
			long value = packed[index * 4 + word];

			for (int position = 0; position < 8; position++) {
				hash[word * 8 + position] = (byte) (value >>> (56 - position * 8));
			}
		}

		return hash;
	}

	/** A reading taken after a collection, so the baseline is not last run's litter. */
	private long settle() {
		System.gc();

		return usedHeap();
	}

	private double megabytes(long bytes) {
		return bytes / (1024.0 * 1024.0);
	}

	private double seconds(long nanos) {
		return nanos / 1_000_000_000.0;
	}

	private long usedHeap() {
		Runtime runtime = Runtime.getRuntime();

		return runtime.totalMemory() - runtime.freeMemory();
	}
}