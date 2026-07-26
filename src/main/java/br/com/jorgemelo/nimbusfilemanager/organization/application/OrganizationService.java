package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.MetadataRebuildService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildRequest;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MetadataRebuildField;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPlan;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPreviewRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationUndoResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

@Service
public class OrganizationService {

	private final OrganizationPlanner organizationPlanner;
	private final OrganizationExecutor organizationExecutor;
	private final MetadataRebuildService metadataRebuildService;
	private final OperationLockService operationLockService;
	private final OrganizationPathValidator organizationPathValidator;
	private final OrganizationUndoService organizationUndoService;
	private final OrganizationReconcileService organizationReconcileService;
	private final OrganizationAsyncRunner organizationAsyncRunner;
	private final OrganizationPlanStore organizationPlanStore;
	private final ExecutionRepository executionRepository;
	private final ExecutionMapper executionMapper;
	private final Clock clock;

	@Autowired
	public OrganizationService(OrganizationPlanner organizationPlanner, OrganizationExecutor organizationExecutor,
			MetadataRebuildService metadataRebuildService, OperationLockService operationLockService,
			OrganizationPathValidator organizationPathValidator, OrganizationUndoService organizationUndoService,
			OrganizationReconcileService organizationReconcileService, OrganizationAsyncRunner organizationAsyncRunner,
			OrganizationPlanStore organizationPlanStore, ExecutionRepository executionRepository,
			ExecutionMapper executionMapper, Clock clock) {
		this.organizationPlanner = organizationPlanner;
		this.organizationExecutor = organizationExecutor;
		this.metadataRebuildService = metadataRebuildService;
		this.operationLockService = operationLockService;
		this.organizationPathValidator = organizationPathValidator;
		this.organizationUndoService = organizationUndoService;
		this.organizationReconcileService = organizationReconcileService;
		this.organizationAsyncRunner = organizationAsyncRunner;
		this.organizationPlanStore = organizationPlanStore;
		this.executionRepository = executionRepository;
		this.executionMapper = executionMapper;
		this.clock = clock;
	}

	public OrganizationPlan preview(OrganizationPreviewRequest request) {
		organizationPathValidator.validate(request.source(), request.target());

		rebuildMetadataIfRequested(request);

		return organizationPlanner.preview(request);
	}

	public OrganizationExecuteResponse execute(OrganizationExecuteRequest request) {
		organizationPathValidator.validate(request.source(), request.target());

		try (var _ = operationLockService.acquire(ExecutionType.ORGANIZATION, request.source(),
				request.target())) {
			rebuildMetadataIfRequested(request.toPreviewRequest());

			return organizationExecutor.execute(request);
		} catch (OperationLockException e) {
			return new OrganizationExecuteResponse((UUID) null, ExecutionStatus.ERROR.name(), null, null,
					request.source().toString(), request.target().toString(), 0, 0, 0, 1, true,
					"Organization rejected: " + e.getMessage());
		}
	}

	/**
	 * Creates the execution record and hands the actual preview off to a background
	 * thread, returning immediately so the web layer can redirect to a progress
	 * screen. The resulting OrganizationPlan (which can hold hundreds of thousands
	 * of items) is kept in OrganizationPlanStore, retrievable via getPreviewPlan
	 * once the execution finishes.
	 */
	public ExecutionResponse previewAsync(OrganizationExecuteRequest request) {
		organizationPathValidator.validate(request.source(), request.target());

		rebuildMetadataIfRequested(request.toPreviewRequest());

		Execution execution = startExecution(request.source(), request.target(), request.recursiveValue(), false,
				ExecutionMessages.PREVIEW_STARTED);

		// Preview runs the same executor as execute, in dry-run: the request already
		// carries dryRun=true, so no file is moved and no row is written.
		organizationAsyncRunner.runPreview(request, execution);

		return executionMapper.toResponse(execution);
	}

	/**
	 * Creates the execution record and hands the actual move off to a background
	 * thread, returning immediately so the web layer can redirect to a progress
	 * screen.
	 */
	public ExecutionResponse executeAsync(OrganizationExecuteRequest request) {
		organizationPathValidator.validate(request.source(), request.target());

		Execution execution = startExecution(request.source(), request.target(), request.recursiveValue(), true,
				ExecutionMessages.ORGANIZATION_STARTED);

		organizationAsyncRunner.runExecute(request, execution);

		return executionMapper.toResponse(execution);
	}

	public OrganizationPlan getPreviewPlan(Long executionId) {
		return organizationPlanStore.get(executionId);
	}

	public OrganizationPlan getPreviewPlanPublic(UUID executionId) {
		return organizationPlanStore.get(findExecution(executionId).getId());
	}

	private Execution startExecution(Path source, Path target, boolean recursive, boolean executeFlag,
			String messageCode) {
		Execution execution = Execution.builder().executionType(ExecutionType.ORGANIZATION)
				.status(ExecutionStatus.STARTED).startedAt(LocalDateTime.now(clock))
				.sourcePath(PathUtils.normalize(source)).targetPath(PathUtils.normalize(target)).recursive(recursive)
				.executeFlag(executeFlag).statusMessage(StatusMessage.code(messageCode)).filesFound(0).filesAnalyzed(0)
				.cacheHits(0)
				.filesMoved(0).simulatedFiles(0).errors(0).build();

		return executionRepository.save(execution);
	}

	public OrganizationUndoResponse undoPublic(UUID executionId) {
		return organizationUndoService.undo(findExecution(executionId).getId());
	}

	private Execution findExecution(UUID publicId) {
		return executionRepository.findByPublicId(publicId)
				.orElseThrow(() -> new IllegalArgumentException("Execution not found: " + publicId));
	}

	public OrganizationReconcileResponse reconcile(OrganizationReconcileRequest request) {
		return organizationReconcileService.reconcile(request);
	}

	private void rebuildMetadataIfRequested(OrganizationPreviewRequest request) {
		if (request.rebuildMetadataValue()) {
			metadataRebuildService.rebuild(new MetadataRebuildRequest(request.sourcePath(),
					request.rebuild() == null || request.rebuild().isEmpty() ? List.of(MetadataRebuildField.DATE)
							: request.rebuild(),
					null, null, request.limit(), false, null));
		}
	}
}