package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.util.Set;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * Whether this installation has ever finished cataloguing its library.
 *
 * <p>
 * A different question from {@link InventoryRunningState}, which asks whether
 * an inventory is happening <em>now</em>. This one asks whether one ever
 * completed, and the two are needed together: background work that must not
 * compete with the first cataloguing has to know both that none is running and
 * that the first one is behind it.
 *
 * <p>
 * <b>Why completion and not "are there files".</b> Asking the catalog would
 * answer yes halfway through the very pass this exists to stay out of the way
 * of, and would answer no for a library that legitimately holds nothing. The
 * boundary is the run, not its yield.
 */
@Component
public class InventoryBootstrapState {

	/**
	 * The two outcomes that mean the walk reached the end of the library.
	 *
	 * <p>
	 * {@code FINISHED_WITH_ERRORS} counts, and that is the whole judgement call
	 * here. Across this project the pair is one dichotomy - a pass that completed
	 * with some individual items failing, against a pass that failed as a whole,
	 * which is {@code ERROR}. An inventory reports errors per file: something it
	 * could not read or stat. The tree was still walked and the catalog still
	 * reflects it, so the bootstrap is over.
	 *
	 * <p>
	 * Everything else is deliberately excluded. {@code CANCELLED} and
	 * {@code INTERRUPTED} stopped part-way, so the catalog covers only what they
	 * reached; {@code ERROR} covers both a walk that broke and one refused because
	 * another operation held the tree; {@code REJECTED} never started.
	 */
	private static final Set<ExecutionStatus> WALKED_THE_WHOLE_LIBRARY = Set.of(ExecutionStatus.FINISHED,
			ExecutionStatus.FINISHED_WITH_ERRORS);

	private final ExecutionRepository executionRepository;

	public InventoryBootstrapState(ExecutionRepository executionRepository) {
		this.executionRepository = executionRepository;
	}

	/**
	 * Asked from a timer, so it is an existence check rather than a count or a
	 * scan: the answer flips once in the life of an installation and never flips
	 * back, but it is read from the database every time because the database is
	 * what knows.
	 */
	public boolean hasCompletedAtLeastOnce() {
		return executionRepository.existsByExecutionTypeAndStatusIn(ExecutionType.INVENTORY,
				WALKED_THE_WHOLE_LIBRARY);
	}
}