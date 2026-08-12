package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionStatusNames;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionActivity;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionActivitySnapshot;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ExecutionLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

/**
 * What is happening right now, for the banner every authenticated page carries.
 *
 * <p>
 * It asks the queue and nothing else. Before this, the banner was told about one
 * execution when the page rendered and then followed that id - so work started a
 * second later was invisible until somebody pressed F5, and when the one it
 * followed ended it stopped looking. Asking "what is active?" instead has no id
 * to go stale, which is what makes discovery work at all.
 *
 * <p>
 * The reading is deliberately cheap, because it happens on every page every few
 * seconds: one query over the active statuses, served by the index on
 * {@code status}, and the active set is small by construction - deduplication
 * allows one waiting and one running per key, and each type runs one at a time.
 * Nothing here touches the history or the steps, and the labels and both
 * percentages come from what is already in hand.
 */
@Service
public class ExecutionActivityService {

	/**
	 * The order the banner shows things in, decided here rather than in the page:
	 * something running outranks something waiting, a higher priority outranks a
	 * lower one, and between equals the one that has waited longest comes first. A
	 * page sorting this itself would be a second opinion about the queue.
	 *
	 * <p>
	 * It reads the same way the queue does but is not the queue's decision: the
	 * reservation adds an ageing bonus to priority so nothing starves, and that
	 * belongs to choosing what to run, not to describing what is happening. This
	 * only has to put the same work at the top, and it does.
	 */
	private static final Comparator<Execution> QUEUE_ORDER = Comparator
			.comparing((Execution execution) -> execution.getStatus() == ExecutionStatus.RUNNING ? 0 : 1)
			.thenComparing(Execution::getPriority, Comparator.nullsLast(Comparator.reverseOrder()))
			.thenComparing(Execution::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()));

	private final ExecutionRepository executionRepository;
	private final ExecutionLabels executionLabels;
	private final ExecutionMapper executionMapper;

	public ExecutionActivityService(ExecutionRepository executionRepository, ExecutionLabels executionLabels,
			ExecutionMapper executionMapper) {
		this.executionRepository = executionRepository;
		this.executionLabels = executionLabels;
		this.executionMapper = executionMapper;
	}

	public ExecutionActivitySnapshot current() {
		List<ExecutionActivity> active = executionRepository.findByStatusIn(ExecutionStatusNames.ACTIVE).stream()
				.sorted(QUEUE_ORDER).map(this::toActivity).toList();

		if (active.isEmpty()) {
			return ExecutionActivitySnapshot.idle();
		}

		List<ExecutionActivity> others = active.subList(1, active.size());

		return new ExecutionActivitySnapshot(active.getFirst(), others, running(active),
				active.size() - running(active),
				executionLabels.othersInFlight(running(others), others.size() - running(others)));
	}

	/**
	 * Being worked on, as opposed to waiting for a worker. Only these two states
	 * are active at all, so what is not running is queued.
	 */
	private int running(List<ExecutionActivity> activities) {
		return (int) activities.stream().filter(activity -> ExecutionStatus.RUNNING.name().equals(activity.status()))
				.count();
	}

	private ExecutionActivity toActivity(Execution execution) {
		return new ExecutionActivity(UuidV7.orLegacy(execution.getExecutionPublicId(), execution.getId()),
				execution.getExecutionType().name(), executionLabels.type(execution.getExecutionType()),
				execution.getStatus().name(), executionLabels.status(execution.getStatus()), execution.getSourcePath(),
				executionMapper.percentComplete(execution), executionMapper.currentItemPercent(execution),
				execution.getFilesFound(), execution.getTotalExpected(),
				Boolean.TRUE.equals(execution.getCancelRequested()), href(execution));
	}

	private String href(Execution execution) {
		return "/app/executions/" + UuidV7.orLegacy(execution.getExecutionPublicId(), execution.getId());
	}
}