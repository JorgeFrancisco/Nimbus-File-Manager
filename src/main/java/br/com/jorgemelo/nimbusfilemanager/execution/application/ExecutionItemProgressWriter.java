package br.com.jorgemelo.nimbusfilemanager.execution.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * Writes how far into the current item a run has got.
 *
 * <p>
 * A bean of its own, and not a method beside the throttle that decides whether
 * to call it: a transactional method invoked from inside its own class never
 * passes the proxy, so the transaction it declares does not exist and the
 * change to a detached entity reaches nothing. Keeping the write here is what
 * makes the declaration true.
 *
 * <p>
 * The separation is also the point of the throttle: deciding not to write must
 * not cost the round trip that opening a transaction would.
 */
@Service
public class ExecutionItemProgressWriter {

	private final ExecutionRepository executionRepository;

	public ExecutionItemProgressWriter(ExecutionRepository executionRepository) {
		this.executionRepository = executionRepository;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void write(long executionId, int percent) {
		found(executionId).setCurrentItemPercent(percent);
	}

	/**
	 * Takes the number away rather than setting it to zero, for a stretch of work
	 * that has nothing to count. Null is what the read side already treats as "no
	 * second bar", so nothing downstream has to learn a new case.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void clear(long executionId) {
		found(executionId).setCurrentItemPercent(null);
	}

	private Execution found(long executionId) {
		return executionRepository.findById(executionId)
				.orElseThrow(() -> new IllegalStateException("Execution not found: " + executionId));
	}
}