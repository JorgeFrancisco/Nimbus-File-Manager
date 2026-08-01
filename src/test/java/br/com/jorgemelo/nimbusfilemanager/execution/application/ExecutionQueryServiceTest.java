package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.model.ExecutionError;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.ExecutionErrorRepository;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.projection.ExecutionErrorSummaryResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ExecutionLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.ExecutionStatusNames;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.ExecutionStep;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionStepRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.MovementSummaryResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

@ExtendWith(MockitoExtension.class)
class ExecutionQueryServiceTest {

	@Mock
	private ExecutionRepository executionRepository;

	@Mock
	private ExecutionStepRepository executionStepRepository;

	@Mock
	private ExecutionErrorRepository executionErrorRepository;

	@Mock
	private MovementRepository movementRepository;

	private final ExecutionMapper executionMapper = new ExecutionMapper(new ExecutionMessageCodec(new ObjectMapper()),
			new ExecutionLabels());

	@Test
	void listShouldMapExecutions() {
		when(executionRepository.findTop20ByOrderByStartedAtDesc()).thenReturn(List.of(execution(1L)));

		Assertions.assertThat(service().list()).hasSize(1);
	}

	@Test
	void pageShouldMapExecutionsAndPreserveHasNext() {
		Execution execution = execution(1L);

		// page 1 of size 20 with 45 total elements -> pages 0,1,2 exist, so page 1
		// still has a next one.
		Page<Execution> executionPage = new PageImpl<>(List.of(execution), PageRequest.of(1, 20), 45);

		when(executionRepository.findAllByOrderByStartedAtDesc(PageRequest.of(1, 20))).thenReturn(executionPage);

		Page<ExecutionResponse> result = service().page(1, 20);

		Assertions.assertThat(result.getContent()).hasSize(1);
		Assertions.assertThat(result.getContent().getFirst().executionId()).isEqualTo(UuidV7.fromLegacy(1L));
		Assertions.assertThat(result.hasNext()).isTrue();
		Assertions.assertThat(result.getTotalElements()).isEqualTo(45);
	}

	@Test
	void activeShouldReturnLatestRunningExecution() {
		Execution execution = execution(1L);

		execution.setStatus(ExecutionStatus.PROCESSING_FILES);

		when(executionRepository
				.findFirstByFinishedAtIsNullAndStatusInOrderByStartedAtDesc(ExecutionStatusNames.IN_PROGRESS))
						.thenReturn(Optional.of(execution));

		Assertions.assertThat(service().active()).get().extracting(ExecutionResponse::executionId)
				.isEqualTo(UuidV7.fromLegacy(1L));
	}

	@Test
	void mapperShouldReturnNullForNullExecution() {
		Assertions.assertThat(executionMapper.toResponse(null)).isNull();
	}

	@Test
	void mapperShouldComputeClampedPercentCompleteOrNullWhenTotalOrProcessedAreMissing() {
		Execution overHundredPercent = Execution.builder().id(1L).executionType(ExecutionType.INVENTORY)
				.status(ExecutionStatus.PROCESSING_FILES).startedAt(LocalDateTime.now()).totalExpected(10)
				.filesFound(15).build();

		Execution zeroTotal = Execution.builder().id(2L).executionType(ExecutionType.INVENTORY)
				.status(ExecutionStatus.PROCESSING_FILES).startedAt(LocalDateTime.now()).totalExpected(0).filesFound(5)
				.build();

		Execution missingProcessed = Execution.builder().id(3L).executionType(ExecutionType.INVENTORY)
				.status(ExecutionStatus.PROCESSING_FILES).startedAt(LocalDateTime.now()).totalExpected(10)
				.filesFound(null).build();

		Assertions.assertThat(executionMapper.toResponse(overHundredPercent).percentComplete()).isEqualTo(100.0);
		Assertions.assertThat(executionMapper.toResponse(zeroTotal).percentComplete()).isNull();
		Assertions.assertThat(executionMapper.toResponse(missingProcessed).percentComplete()).isNull();
	}

	@Test
	void uuidLookupsShouldResolveByPublicIdAndMapDetails() {
		UUID publicId = UUID.randomUUID();

		Execution execution = execution(1L);

		ExecutionStep step = ExecutionStep.builder().id(10L).execution(execution).stepType(ExecutionStepType.FINISHED)
				.path("C:/f.jpg").statusMessage(StatusMessage.raw("done")).filesFound(1).filesAnalyzed(1).cacheHits(0)
				.errors(0).createdAt(LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0)).build();

		ExecutionError error = ExecutionError.builder().id(20L).execution(execution).path("C:/f.jpg")
				.errorType(ExecutionErrorType.METADATA_ERROR).errorMessage("bad metadata")
				.createdAt(LocalDateTime.of(2024, Month.JANUARY, 1, 11, 0)).build();

		Movement movement = Movement.builder().id(3L).execution(execution)
				.catalogFile(CatalogFile.builder().id(2L).build()).sourcePath("C:/a.jpg").targetPath("C:/b.jpg")
				.status(MovementStatus.MOVED).reason(MovementReason.NONE)
				.movedAt(LocalDateTime.of(2024, Month.JANUARY, 1, 12, 0)).build();

		when(executionRepository.findByPublicId(publicId)).thenReturn(Optional.of(execution));
		when(executionStepRepository.findByExecutionIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(step));
		when(executionErrorRepository.findByExecutionIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(error));
		when(executionErrorRepository.summarizeByExecutionId(1L))
				.thenReturn(List.of(new ExecutionErrorSummaryResponse("METADATA_ERROR", 3)));
		when(movementRepository.findByExecutionIdOrderByIdAsc(1L)).thenReturn(List.of(movement));
		when(movementRepository.summarizeByExecutionId(1L))
				.thenReturn(List.of(new MovementSummaryResponse("MOVED", null, 900),
						new MovementSummaryResponse("SKIPPED", "ALREADY_MOVED", 80),
						new MovementSummaryResponse("ERROR", "INTEGRITY_CHECK_FAILED", 2)));

		ExecutionQueryService service = service();

		Assertions.assertThat(service.get(publicId).executionType()).isEqualTo(ExecutionType.INVENTORY.name());
		Assertions.assertThat(service.internalId(publicId)).isEqualTo(1L);
		Assertions.assertThat(service.steps(publicId).getFirst().stepType())
				.isEqualTo(ExecutionStepType.FINISHED.name());
		Assertions.assertThat(service.errors(publicId).getFirst().errorType())
				.isEqualTo(ExecutionErrorType.METADATA_ERROR.name());
		Assertions.assertThat(service.errorSummary(publicId)).hasSize(1);

		var movementResponse = service.movements(publicId).getFirst();

		Assertions.assertThat(movementResponse.id()).isEqualTo(UuidV7.fromLegacy(3L));
		Assertions.assertThat(movementResponse.executionId()).isEqualTo(UuidV7.fromLegacy(1L));
		Assertions.assertThat(movementResponse.catalogFileId()).isEqualTo(UuidV7.fromLegacy(2L));
		Assertions.assertThat(movementResponse.status()).isEqualTo(MovementStatus.MOVED.name());

		var summary = service.movementSummary(publicId);

		Assertions.assertThat(summary).hasSize(3);
		Assertions.assertThat(summary.getFirst().reason()).isNull();
		Assertions.assertThat(summary.get(2).reason()).isEqualTo("INTEGRITY_CHECK_FAILED");
	}

	@Test
	void uuidLookupShouldThrowWhenPublicIdIsMissing() {
		UUID publicId = UUID.randomUUID();

		when(executionRepository.findByPublicId(publicId)).thenReturn(Optional.empty());

		assertThatIllegalArgumentException().isThrownBy(() -> service().get(publicId))
				.withMessageContaining("Execution not found");
	}

	@Test
	void mappersShouldHandleNullExecutionAndNullCatalogFile() {
		UUID publicId = UUID.randomUUID();

		Execution execution = execution(1L);

		ExecutionError errorWithoutExecution = ExecutionError.builder().id(20L).execution(null).path("C:/x.jpg")
				.errorType(ExecutionErrorType.METADATA_ERROR).errorMessage("m").createdAt(LocalDateTime.now()).build();

		Movement movementWithoutCatalogFile = Movement.builder().id(3L).execution(execution).catalogFile(null)
				.sourcePath("C:/a").targetPath("C:/b").status(MovementStatus.SKIPPED).reason(null)
				.movedAt(LocalDateTime.now()).build();

		when(executionRepository.findByPublicId(publicId)).thenReturn(Optional.of(execution));
		when(executionErrorRepository.findByExecutionIdOrderByCreatedAtAsc(1L))
				.thenReturn(List.of(errorWithoutExecution));
		when(movementRepository.findByExecutionIdOrderByIdAsc(1L)).thenReturn(List.of(movementWithoutCatalogFile));

		ExecutionQueryService service = service();

		Assertions.assertThat(service.errors(publicId).getFirst().executionId()).isNull();

		var movementResponse = service.movements(publicId).getFirst();

		Assertions.assertThat(movementResponse.catalogFileId()).isNull();
		Assertions.assertThat(movementResponse.reason()).isNull();
	}

	private ExecutionQueryService service() {
		return new ExecutionQueryService(executionRepository, executionStepRepository, executionErrorRepository,
				movementRepository, executionMapper);
	}

	private Execution execution(Long id) {
		return Execution.builder().id(id).executionType(ExecutionType.INVENTORY).status(ExecutionStatus.FINISHED)
				.startedAt(LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0))
				.finishedAt(LocalDateTime.of(2024, Month.JANUARY, 1, 11, 0)).sourcePath("C:/input")
				.targetPath("C:/target").filesFound(1).filesAnalyzed(1).cacheHits(0).filesMoved(0).simulatedFiles(0)
				.errors(0).statusMessage(StatusMessage.raw("done")).build();
	}
}