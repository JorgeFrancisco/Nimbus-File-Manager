package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.util.List;

import org.springframework.boot.test.context.TestComponent;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueue;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Organises in an integration test the way the product does it: the request is
 * queued, then claimed off the queue, then locked, then handed to the handler.
 *
 * <p>
 * Nothing here stands in for anything. The claim is the real
 * {@code ExecutionQueue.reserve}, so the row is genuinely RUNNING with a lease
 * on it, and the ownership is a real advisory lock in a session of its own -
 * which is what makes the checkpoint between files a real question rather than
 * a stub that always says yes. What it leaves out is the worker loop, which
 * decides <em>when</em> to claim and is not what these tests are about.
 */
@TestComponent
public class OrganizationJobTestRunner {

	private static final String WORKER_ID = "test-worker";
	private static final int MAX_CLAIMS = 3;
	private static final int LEASE_SECONDS = 60;

	private final OrganizationLauncherService organizationLauncherService;
	private final OrganizationJobHandler organizationJobHandler;
	private final OperationLockService operationLockService;
	private final ExecutionQueue executionQueue;
	private final ExecutionRepository executionRepository;

	public OrganizationJobTestRunner(OrganizationLauncherService organizationLauncherService,
			OrganizationJobHandler organizationJobHandler, OperationLockService operationLockService,
			ExecutionQueue executionQueue, ExecutionRepository executionRepository) {
		this.organizationLauncherService = organizationLauncherService;
		this.organizationJobHandler = organizationJobHandler;
		this.operationLockService = operationLockService;
		this.executionQueue = executionQueue;
		this.executionRepository = executionRepository;
	}

	/**
	 * @return the finished row, reloaded, which is where the counts a screen shows
	 * actually live
	 */
	public Execution organize(OrganizationExecuteRequest request) {
		organizationLauncherService.launch(request);

		ClaimedExecution claimed = executionQueue
				.reserve(WORKER_ID, List.of(ExecutionType.ORGANIZATION.name()), MAX_CLAIMS, LEASE_SECONDS)
				.orElseThrow(() -> new IllegalStateException("The organization that was just queued could not "
						+ "be claimed, so there is nothing to run"));

		Execution execution = executionRepository.findById(claimed.id())
				.orElseThrow(() -> new IllegalStateException("A claimed execution has no row: " + claimed.id()));

		try (ExecutionOwnership ownership = operationLockService.acquireFor(claimed.id(), ExecutionType.ORGANIZATION,
				PathUtils.normalizePath(claimed.sourcePath()), PathUtils.normalizePath(claimed.targetPath()))) {
			organizationJobHandler.handle(execution, claimed, ownership);
		}

		return executionRepository.findById(claimed.id()).orElseThrow(
				() -> new IllegalStateException("The execution disappeared while running: " + claimed.id()));
	}
}