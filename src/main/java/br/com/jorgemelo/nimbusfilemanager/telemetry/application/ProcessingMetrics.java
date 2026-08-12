package br.com.jorgemelo.nimbusfilemanager.telemetry.application;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExternalToolCategory;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.CategorySnapshot;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.Snapshot;

/**
 * Thread-safe accumulator that keeps the cost categories of parallel processing
 * strictly separated, so a report can tell where time actually goes:
 * <ul>
 * <li>time waiting in the executor queue (submitted, not yet started);</li>
 * <li>time waiting on an external-process gate (per
 * {@link ExternalToolCategory});</li>
 * <li>real execution time of ffmpeg/ffprobe (per category);</li>
 * <li>total wall time per task;</li>
 * <li>maximum observed concurrency;</li>
 * <li>counts of tasks executed, avoided by cache, cancelled and failed.</li>
 * </ul>
 *
 * <p>
 * <b>Accumulated vs wall-clock:</b> under parallelism the sum of individual
 * durations is expected to exceed the elapsed wall-clock time (several ffmpeg
 * run at once). The report must show both — accumulated ({@code *Nanos} sums)
 * and the real elapsed time ({@link #recordWallClock}) — and never treat the
 * raw sum of ffmpeg durations as a direct reduction of total time.
 */
public class ProcessingMetrics {

	private final LongAdder tasksExecuted = new LongAdder();
	private final LongAdder tasksCacheAvoided = new LongAdder();
	private final LongAdder tasksCancelled = new LongAdder();
	private final LongAdder tasksError = new LongAdder();

	private final LongAdder queueWaitNanos = new LongAdder();
	private final LongAdder taskTotalNanos = new LongAdder();
	private final LongAdder wallClockNanos = new LongAdder();

	private final Map<ExternalToolCategory, LongAdder> gateWaitNanos = newCategoryMap();
	private final Map<ExternalToolCategory, LongAdder> externalExecNanos = newCategoryMap();
	private final Map<ExternalToolCategory, LongAdder> externalRuns = newCategoryMap();

	private final AtomicInteger maxConcurrency = new AtomicInteger();

	public void incExecuted() {
		tasksExecuted.increment();
	}

	public void incCacheAvoided() {
		tasksCacheAvoided.increment();
	}

	public void incCacheAvoided(long count) {
		tasksCacheAvoided.add(count);
	}

	public void incCancelled() {
		tasksCancelled.increment();
	}

	public void incError() {
		tasksError.increment();
	}

	public void recordQueueWait(long nanos) {
		queueWaitNanos.add(nanos);
	}

	public void recordTaskTotal(long nanos) {
		taskTotalNanos.add(nanos);
	}

	public void recordWallClock(long nanos) {
		wallClockNanos.add(nanos);
	}

	public void recordGateWait(ExternalToolCategory category, long nanos) {
		gateWaitNanos.get(category).add(nanos);
	}

	public void recordExternalExec(ExternalToolCategory category, long nanos) {
		externalExecNanos.get(category).add(nanos);
		externalRuns.get(category).increment();
	}

	/** Records a newly observed concurrency level, keeping the running maximum. */
	public void updateMaxConcurrency(int observed) {
		maxConcurrency.accumulateAndGet(observed, Math::max);
	}

	public Snapshot snapshot() {
		Map<ExternalToolCategory, CategorySnapshot> categories = new EnumMap<>(ExternalToolCategory.class);

		for (ExternalToolCategory category : ExternalToolCategory.values()) {
			categories.put(category, new CategorySnapshot(externalRuns.get(category).sum(),
					gateWaitNanos.get(category).sum(), externalExecNanos.get(category).sum()));
		}

		return new Snapshot(tasksExecuted.sum(), tasksCacheAvoided.sum(), tasksCancelled.sum(), tasksError.sum(),
				queueWaitNanos.sum(), taskTotalNanos.sum(), wallClockNanos.sum(), maxConcurrency.get(), categories);
	}

	private static Map<ExternalToolCategory, LongAdder> newCategoryMap() {
		Map<ExternalToolCategory, LongAdder> map = new EnumMap<>(ExternalToolCategory.class);

		for (ExternalToolCategory category : ExternalToolCategory.values()) {
			map.put(category, new LongAdder());
		}

		return map;
	}
}