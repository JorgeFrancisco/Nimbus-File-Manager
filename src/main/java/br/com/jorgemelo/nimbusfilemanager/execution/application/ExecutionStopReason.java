package br.com.jorgemelo.nimbusfilemanager.execution.application;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import lombok.extern.slf4j.Slf4j;

/**
 * Why a run may not touch the next item, or nothing when it may carry on.
 *
 * <p>
 * Two things stop a run that is otherwise healthy, and neither is a failure of
 * the work: a person asked it to stop, and the locks under it went away. Both
 * are asked immediately before each item rather than after it, because what has
 * already happened to the user's files cannot be taken back - and both end the
 * execution in a status that says what happened instead of blaming the job for
 * the moment it was standing in.
 *
 * <p>
 * Here rather than repeated in each writer: the question and its two answers
 * are the same everywhere, only the sentence each writer records differs.
 */
@Slf4j
@Service
public class ExecutionStopReason {

	private final ExecutionCancellationService executionCancellationService;

	public ExecutionStopReason(ExecutionCancellationService executionCancellationService) {
		this.executionCancellationService = executionCancellationService;
	}

	/**
	 * @return {@link ExecutionStatus#CANCELLED} when somebody asked it to stop,
	 * {@link ExecutionStatus#INTERRUPTED} when the paths are no longer owned, or
	 * {@code null} when there is no reason to stop
	 */
	public ExecutionStatus of(Execution execution, ExecutionOwnership ownership) {
		if (executionCancellationService.isCancelled(execution.getId())) {
			return ExecutionStatus.CANCELLED;
		}

		try {
			ownership.assertStillOwned();
		} catch (OwnershipLostException ownershipLost) {
			log.warn("Execution {} stopped: {}", execution.getId(), ownershipLost.getMessage());

			return ExecutionStatus.INTERRUPTED;
		}

		return null;
	}
}