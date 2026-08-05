package br.com.jorgemelo.nimbusfilemanager.worker.application;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;

/**
 * How many executions of each kind this worker will run at once.
 *
 * <p>
 * A third limit, and not a repetition of the two that exist. The loops bound
 * how many executions run in total; {@code ProcessingCoordinator} bounds how
 * many files are in flight inside one of them; {@code ExternalToolGate} bounds
 * how many ffmpeg processes exist. None of the three can say "one conversion at
 * a time" - the gate would happily let three conversions share its permits, each
 * with its own workspace file, its own memory and its own hours.
 *
 * <p>
 * Nor is deduplication this. Dedup asks whether the same request is already
 * queued, and the requests a person makes are deliberately not deduplicated -
 * two conversions of two different videos are two different jobs. What they must
 * not be is simultaneous, and that is a different question with a different
 * answer.
 *
 * <p>
 * The number comes from the handler, because the handler is what knows. Today it
 * is what an {@code AtomicBoolean} in each old runner already enforces - one at
 * a time - and stating it here is what keeps that guarantee when the runner goes
 * away.
 *
 * <p>
 * Capacity is checked before claiming rather than after. A worker that reserved
 * a row and then waited for a slot would be holding an execution it is not
 * running, with a lease ticking, where another worker could have taken it.
 */
@Component
@Profile(NimbusProfiles.WORKER)
public class CategoryConcurrency {

	private final Map<ExecutionType, Semaphore> slots = new EnumMap<>(ExecutionType.class);

	public CategoryConcurrency(List<ExecutionJobHandler> handlers) {
		handlers.forEach(handler -> slots.put(handler.type(), new Semaphore(handler.concurrencyLimit())));
	}

	/**
	 * The types worth asking the queue about right now.
	 *
	 * <p>
	 * A saturated type is left out of the question entirely, so a worker whose
	 * conversions are all busy goes on claiming inventories - which is the
	 * difference between a limit and a queue that stops.
	 */
	public List<String> typesWithCapacity() {
		return slots.entrySet().stream().filter(slot -> slot.getValue().availablePermits() > 0).map(Map.Entry::getKey)
				.map(Enum::name).toList();
	}

	/**
	 * Takes a slot, or says there was none. Never waits: the caller has a claimed
	 * execution in hand and hands it straight back rather than holding it.
	 */
	public boolean tryEnter(ExecutionType executionType) {
		Semaphore slot = slots.get(executionType);

		return slot != null && slot.tryAcquire();
	}

	public void leave(ExecutionType executionType) {
		Semaphore slot = slots.get(executionType);

		if (slot != null) {
			slot.release();
		}
	}
}