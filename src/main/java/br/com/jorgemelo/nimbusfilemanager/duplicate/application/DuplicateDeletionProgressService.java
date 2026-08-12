package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.Optional;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateDeletionProgress;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateDeletionResult;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.NumberUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.ProgressMath;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

/**
 * What the duplicates screen asks while files are being sent to quarantine, and
 * afterwards.
 *
 * <p>
 * All of it comes from the row. Unlike the conversion there is nothing finer to
 * report - a file either moved or it did not, and none of them takes hours - so
 * the counters the execution already keeps say everything the bar needs, and
 * the final report is those same counters with the message beside them.
 */
@Service
public class DuplicateDeletionProgressService {

	/** Nothing was ever asked for, so nothing runs and there is nothing to show. */
	private static final DuplicateDeletionProgress IDLE = new DuplicateDeletionProgress(false, 0, 0, 0, null);

	private final ExecutionRepository executionRepository;

	public DuplicateDeletionProgressService(ExecutionRepository executionRepository) {
		this.executionRepository = executionRepository;
	}

	public DuplicateDeletionProgress snapshot() {
		return latest().map(this::snapshotOf).orElse(IDLE);
	}

	public Optional<Execution> active() {
		return latest().filter(execution -> !execution.getStatus().isTerminal());
	}

	private Optional<Execution> latest() {
		return executionRepository.findFirstByExecutionTypeOrderByCreatedAtDesc(ExecutionType.DEDUP_DELETE);
	}

	private DuplicateDeletionProgress snapshotOf(Execution execution) {
		int total = NumberUtils.toInt(
				execution.getTotalExpected() == null ? execution.getFilesFound() : execution.getTotalExpected());
		int processed = NumberUtils.toInt(execution.getFilesAnalyzed());

		if (execution.getStatus().isTerminal()) {
			return new DuplicateDeletionProgress(false, processed, total, 100, reportOf(execution, total));
		}

		return new DuplicateDeletionProgress(true, processed, total,
				ProgressMath.round(ProgressMath.percent(processed, total)), null);
	}

	private DuplicateDeletionResult reportOf(Execution execution, int total) {
		return new DuplicateDeletionResult(true, total, NumberUtils.toInt(execution.getFilesMoved()),
				NumberUtils.toInt(execution.getCacheHits()), NumberUtils.toInt(execution.getErrors()),
				UuidV7.orLegacy(execution.getExecutionPublicId(), execution.getId()),
				execution.getStatusMessage() == null ? null : execution.getStatusMessage().getText());
	}
}