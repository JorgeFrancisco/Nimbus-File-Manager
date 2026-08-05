package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.StoredPlanPage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

@Service
public class OrganizationService {

	private final OrganizationPathValidator organizationPathValidator;
	private final OrganizationUndoLauncherService organizationUndoLauncherService;
	private final OrganizationReconcileService organizationReconcileService;
	private final OrganizationLauncherService organizationLauncherService;
	private final OrganizationPreviewLauncher organizationPreviewLauncher;
	private final OrganizationPlanReader organizationPlanReader;
	private final ExecutionRepository executionRepository;

	@Autowired
	public OrganizationService(OrganizationPathValidator organizationPathValidator,
			OrganizationUndoLauncherService organizationUndoLauncherService,
			OrganizationReconcileService organizationReconcileService,
			OrganizationLauncherService organizationLauncherService,
			OrganizationPreviewLauncher organizationPreviewLauncher, OrganizationPlanReader organizationPlanReader,
			ExecutionRepository executionRepository) {
		this.organizationPathValidator = organizationPathValidator;
		this.organizationUndoLauncherService = organizationUndoLauncherService;
		this.organizationReconcileService = organizationReconcileService;
		this.organizationLauncherService = organizationLauncherService;
		this.organizationPreviewLauncher = organizationPreviewLauncher;
		this.organizationPlanReader = organizationPlanReader;
		this.executionRepository = executionRepository;
	}

	/**
	 * Asks for a preview and returns at once.
	 *
	 * <p>
	 * Nothing is computed here any more, and that is the point of the change: this
	 * class used to hand the request to a background thread of its own, which
	 * composed the executor - the one class that can move the user's files - just
	 * to find out what moving them would do. The row goes in PENDING now and a
	 * worker builds the plan, so the process serving the screen never holds that
	 * capability and never holds the plan either.
	 */
	public ExecutionResponse previewAsync(OrganizationExecuteRequest request) {
		organizationPathValidator.validate(request.source(), request.target());

		return organizationPreviewLauncher.launch(request);
	}

	/**
	 * Queues the move and returns at once.
	 *
	 * <p>
	 * Nothing is run here any more. The row goes in PENDING and a worker claims
	 * it, which is what took hours of moving files out of the process serving the
	 * screen - and what lets the run carry on when that process is closed.
	 *
	 * <p>
	 * It deliberately does not read the plan. The run recalculates under the state
	 * it finds, which is a decision this codebase records rather than an oversight:
	 * a plan is a description of a moment, and acting on a stale one would be worse
	 * than recalculating. What the screen does with that is warn - see
	 * {@code catalogChanged} on the stored plan.
	 */
	public ExecutionResponse executeAsync(OrganizationExecuteRequest request) {
		organizationPathValidator.validate(request.source(), request.target());

		return organizationLauncherService.launch(request);
	}

	/**
	 * One page of a published plan, or empty when there is nothing to show - which
	 * covers a plan that was never built, one still building, one that failed and
	 * one past its expiry alike, because they are the same answer to a screen.
	 */
	public Optional<StoredPlanPage> planPage(Long executionId, int page, int size, boolean onlyConflicts) {
		return organizationPlanReader.page(executionId, page, size, onlyConflicts);
	}

	public Optional<StoredPlanPage> planPagePublic(UUID executionId, int page, int size, boolean onlyConflicts) {
		return executionRepository.findByPublicId(executionId)
				.flatMap(execution -> organizationPlanReader.page(execution.getId(), page, size, onlyConflicts));
	}

	/**
	 * Queues the reversal and returns at once, like the move it reverses. Undoing
	 * hundreds of files is the same kind of work as making the moves in the first
	 * place, and it stopped being something a request thread waits for.
	 */
	public ExecutionResponse undoPublic(UUID executionId) {
		return organizationUndoLauncherService.launch(findExecution(executionId));
	}

	private Execution findExecution(UUID publicId) {
		return executionRepository.findByPublicId(publicId)
				.orElseThrow(() -> new IllegalArgumentException("Execution not found: " + publicId));
	}

	public OrganizationReconcileResponse reconcile(OrganizationReconcileRequest request) {
		return organizationReconcileService.reconcile(request);
	}
}