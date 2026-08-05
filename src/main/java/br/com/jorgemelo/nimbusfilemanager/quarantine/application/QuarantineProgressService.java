package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineProgress;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.NumberUtils;

/**
 * What the quarantine screen polls once the work happens somewhere else.
 *
 * <p>
 * Every type is asked about together because the screen has one status line and
 * one thing to say: restoring, purging and clearing absent records never
 * overlap in what the person is doing, and whichever ran last is the one they
 * are waiting on.
 */
@Service
public class QuarantineProgressService {

	private static final QuarantineProgress IDLE = new QuarantineProgress(false, null, 0, 0, 0, 0, null);

	private static final List<ExecutionType> BATCHES = List.of(ExecutionType.QUARANTINE_RESTORE,
			ExecutionType.QUARANTINE_PURGE, ExecutionType.QUARANTINE_CLEANUP);

	private final ExecutionRepository executionRepository;
	private final ExecutionMapper executionMapper;

	public QuarantineProgressService(ExecutionRepository executionRepository, ExecutionMapper executionMapper) {
		this.executionRepository = executionRepository;
		this.executionMapper = executionMapper;
	}

	public QuarantineProgress snapshot() {
		return latest().map(this::snapshotOf).orElse(IDLE);
	}

	/**
	 * A request that was never made, in the shape the screen already polls.
	 *
	 * <p>
	 * Nothing is running and no execution exists to describe, so the snapshot of
	 * the last batch would be about somebody's previous purge - which is the one
	 * answer worse than none. What it carries instead is the reason, already
	 * localized, so the screen can say it without knowing anything about it.
	 */
	public QuarantineProgress refused(String message) {
		return new QuarantineProgress(false, null, 0, 0, 0, 0, message);
	}

	/**
	 * The most recent of them, which is the one the screen is waiting on. Asked
	 * per type and compared here because the queue has no "latest of these kinds"
	 * question and inventing one for a handful of rows would cost more than it
	 * saves.
	 */
	private Optional<Execution> latest() {
		return BATCHES.stream()
				.map(executionRepository::findFirstByExecutionTypeOrderByCreatedAtDesc)
				.flatMap(Optional::stream)
				.max((first, second) -> first.getCreatedAt().compareTo(second.getCreatedAt()));
	}

	private QuarantineProgress snapshotOf(Execution execution) {
		return new QuarantineProgress(!execution.getStatus().isTerminal(), execution.getExecutionType().name(),
				NumberUtils.toInt(execution.getFilesFound()), NumberUtils.toInt(execution.getFilesMoved()),
				NumberUtils.toInt(execution.getCacheHits()), NumberUtils.toInt(execution.getErrors()),
				executionMapper.toResponse(execution).message());
	}
}