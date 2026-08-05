package br.com.jorgemelo.nimbusfilemanager.execution.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionStatusNames;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * Tells whether an inventory scan is actually executing - not merely whether
 * the folder monitor is watching. The settings actions that cannot run beside
 * one ask here, so the rule lives in a single place instead of being copied
 * into every caller.
 *
 * <p>
 * Asked of every active row, and not of the most recent one. It used to read
 * "the newest active execution" and compare its type, which answers the
 * question only while one execution runs at a time. A worker runs several, so
 * an inventory that started before a conversion simply stopped being seen: the
 * guard failed open, silently, and according to the order in which two
 * unrelated runs happened to start.
 */
@Component
public class InventoryRunningState {

	private final ExecutionRepository executionRepository;

	@Autowired
	public InventoryRunningState(ExecutionRepository executionRepository) {
		this.executionRepository = executionRepository;
	}

	public boolean isRunning() {
		return executionRepository.existsByExecutionTypeAndStatusIn(ExecutionType.INVENTORY,
				ExecutionStatusNames.ACTIVE);
	}
}