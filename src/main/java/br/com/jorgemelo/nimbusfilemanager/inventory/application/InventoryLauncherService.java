package br.com.jorgemelo.nimbusfilemanager.inventory.application;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationPathKey;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;

/**
 * Entry point the web layer (Onboarding and the manual "Inventário" screen)
 * calls to start an inventory run. Creates the {@link Execution} row, resolves
 * the options once, and hands the pass to the background so the request can
 * redirect to the progress screen straight away.
 */
@Service
public class InventoryLauncherService {

	private final ExecutionEnqueueService executionEnqueueService;
	private final ExecutionMapper executionMapper;
	private final String applicationVersion;

	public InventoryLauncherService(ExecutionEnqueueService executionEnqueueService, ExecutionMapper executionMapper,
			@Value("${application.version}") String applicationVersion) {
		this.executionEnqueueService = executionEnqueueService;
		this.executionMapper = executionMapper;
		this.applicationVersion = applicationVersion;
	}

	/**
	 * Queues an inventory and returns at once.
	 *
	 * <p>
	 * Nothing is started here. The row goes in PENDING and a worker claims it,
	 * which is what puts the scan in another process - and what lets a request
	 * survive this one being closed before the scan begins.
	 *
	 * <p>
	 * A second request for a folder that is already queued is answered with the
	 * execution already waiting: the database refuses the duplicate, and telling
	 * someone "already coming" is truer than opening a second scan that would
	 * refuse itself on the path lock a moment later.
	 *
	 * <p>
	 * It no longer sweeps orphaned executions on the way in. Asking for a scan is
	 * not the moment to judge what other runs are doing, and a lease that lapses
	 * for a moment on a loaded machine would have a click on Inventariar declare a
	 * run interrupted while the worker went on executing it. Abandoned work is
	 * recovered at the start of each process instead, by the one policy that reads
	 * the lease.
	 */
	public ExecutionResponse launch(InventoryRequest request, ExecutionTrigger trigger) {
		Execution queued = Execution.builder().executionType(ExecutionType.INVENTORY).triggerEvent(trigger)
				.sourcePath(request.source().toString()).targetPath(null).recursive(request.recursive())
				.executeFlag(true).applicationVersion(applicationVersion)
				.dedupKey(OperationPathKey.canonical(request.source()))
				.statusMessage(StatusMessage.code(ExecutionMessages.INVENTORY_STARTED)).build();

		return executionMapper.toResponse(executionEnqueueService.enqueueOrExisting(queued));
	}
}