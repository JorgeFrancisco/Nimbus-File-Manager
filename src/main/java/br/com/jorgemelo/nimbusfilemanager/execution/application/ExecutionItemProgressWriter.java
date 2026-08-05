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
	public void write(Execution execution, int percent) {
		executionRepository.findById(execution.getId()).orElseThrow(
				() -> new IllegalStateException("Execution not found: " + execution.getId()))
				.setCurrentItemPercent(percent);
	}
}