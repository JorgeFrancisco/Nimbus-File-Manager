package br.com.jorgemelo.nimbusfilemanager.telemetry.application;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhaseType;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.PhaseSnapshot;

/**
 * Lightweight, in-memory accumulator of the wall time spent in each execution
 * {@link ExecutionPhaseType}. Accumulating a {@code nanoTime} delta per phase
 * is negligible; the values are persisted only once, at the end of the
 * execution.
 *
 * <p>
 * Reset at the start of each execution. Executions never run concurrently (they
 * are serialized by the operation lock / single active execution), so a shared
 * singleton is safe; all counters are {@link LongAdder}s regardless.
 */
public class ExecutionPhaseTimings {

	private final Map<ExecutionPhaseType, LongAdder> durationNanos = newMap();
	private final Map<ExecutionPhaseType, LongAdder> items = newMap();

	public void addNanos(ExecutionPhaseType phase, long nanos) {
		durationNanos.get(phase).add(nanos);
	}

	public void addItems(ExecutionPhaseType phase, long count) {
		items.get(phase).add(count);
	}

	/** Only phases that were actually touched (non-zero duration or item count). */
	public Map<ExecutionPhaseType, PhaseSnapshot> snapshot() {
		Map<ExecutionPhaseType, PhaseSnapshot> snapshot = new LinkedHashMap<>();

		for (ExecutionPhaseType phase : ExecutionPhaseType.values()) {
			long nanos = durationNanos.get(phase).sum();
			long count = items.get(phase).sum();

			if (nanos > 0 || count > 0) {
				snapshot.put(phase, new PhaseSnapshot(TimeUnit.NANOSECONDS.toMillis(nanos), count));
			}
		}

		return snapshot;
	}

	private static Map<ExecutionPhaseType, LongAdder> newMap() {
		Map<ExecutionPhaseType, LongAdder> map = new EnumMap<>(ExecutionPhaseType.class);

		for (ExecutionPhaseType phase : ExecutionPhaseType.values()) {
			map.put(phase, new LongAdder());
		}

		return map;
	}
}