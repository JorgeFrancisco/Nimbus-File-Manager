package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionStatusNames;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionErrorResponse;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionStepResponse;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.MovementResponse;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.model.ExecutionError;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.ExecutionErrorRepository;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.ExecutionStepRepository;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.projection.ExecutionErrorSummaryResponse;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.projection.MovementSummaryResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

@Service
@Transactional(readOnly = true)
public class ExecutionQueryService {

	private final ExecutionRepository executionRepository;
	private final ExecutionStepRepository executionStepRepository;
	private final ExecutionErrorRepository executionErrorRepository;
	private final MovementRepository movementRepository;
	private final ExecutionMapper executionMapper;

	public ExecutionQueryService(ExecutionRepository executionRepository,
			ExecutionStepRepository executionStepRepository, ExecutionErrorRepository executionErrorRepository,
			MovementRepository movementRepository, ExecutionMapper executionMapper) {
		this.executionRepository = executionRepository;
		this.executionStepRepository = executionStepRepository;
		this.executionErrorRepository = executionErrorRepository;
		this.movementRepository = movementRepository;
		this.executionMapper = executionMapper;
	}

	public List<ExecutionResponse> list() {
		return executionRepository.findTop20ByOrderByStartedAtDesc().stream().map(executionMapper::toResponse).toList();
	}

	/**
	 * Paginated variant of {@link #list()}, used by the Dashboard's infinite-scroll
	 * execution table (DashboardWebController) so older executions stay reachable
	 * by scrolling instead of being capped at the most recent 20.
	 */
	public Page<ExecutionResponse> page(int page, int size) {
		return executionRepository.findFunctionalHistory(PageRequest.of(page, size))
				.map(executionMapper::toResponse);
	}

	public Optional<ExecutionResponse> active() {
		return executionRepository
				.findFirstByFinishedAtIsNullAndStatusInOrderByStartedAtDesc(ExecutionStatusNames.ACTIVE)
				.map(executionMapper::toResponse);
	}

	public ExecutionResponse get(UUID id) {
		return executionMapper.toResponse(findByPublicId(id));
	}

	public List<ExecutionStepResponse> steps(UUID id) {
		return executionStepRepository.findByExecutionIdOrderByCreatedAtAsc(findByPublicId(id).getId()).stream()
				.map(executionMapper::toStepResponse).toList();
	}

	public List<ExecutionErrorResponse> errors(UUID id) {
		return executionErrorRepository.findByExecutionIdOrderByCreatedAtAsc(findByPublicId(id).getId()).stream()
				.map(this::toExecutionErrorResponse).toList();
	}

	public List<ExecutionErrorSummaryResponse> errorSummary(UUID id) {
		return executionErrorRepository.summarizeByExecutionId(findByPublicId(id).getId());
	}

	public List<MovementResponse> movements(UUID id) {
		return movementRepository.findByExecutionIdOrderByIdAsc(findByPublicId(id).getId()).stream()
				.map(this::toMovementResponse).toList();
	}

	public List<MovementSummaryResponse> movementSummary(UUID id) {
		return movementRepository.summarizeByExecutionId(findByPublicId(id).getId());
	}

	public Long internalId(UUID publicId) {
		return findByPublicId(publicId).getId();
	}

	private Execution findByPublicId(UUID publicId) {
		return executionRepository.findByPublicId(publicId)
				.orElseThrow(() -> new IllegalArgumentException("Execution not found: " + publicId));
	}

	private ExecutionErrorResponse toExecutionErrorResponse(ExecutionError error) {
		return new ExecutionErrorResponse(UuidV7.orLegacy(error.getPublicId(), error.getId()),
				error.getExecution() == null ? null
						: UuidV7.orLegacy(error.getExecution().getPublicId(), error.getExecution().getId()),
				error.getPath(), error.getErrorType().name(), error.getErrorMessage(), error.getCreatedAt());
	}

	private MovementResponse toMovementResponse(Movement movement) {
		return new MovementResponse(UuidV7.orLegacy(movement.getPublicId(), movement.getId()),
				UuidV7.orLegacy(movement.getExecution().getPublicId(), movement.getExecution().getId()),
				movement.getCatalogFile() == null ? null
						: UuidV7.orLegacy(movement.getCatalogFile().getPublicId(), movement.getCatalogFile().getId()),
				movement.getSourcePath(), movement.getTargetPath(), movement.getStatus().name(),
				movement.getReason() == null ? null : movement.getReason().name(), movement.getMovedAt());
	}
}