package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationFallbackMode;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationSubdivision;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.MetadataRebuildService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildRequest;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MetadataRebuildField;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPlan;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPreviewRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationSummary;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationUndoResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ExecutionLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceTest {

	@Mock
	private OrganizationPlanner organizationPlanner;

	@Mock
	private OrganizationExecutor organizationExecutor;

	@Mock
	private MetadataRebuildService metadataRebuildService;

	@Mock
	private OrganizationUndoService organizationUndoService;

	@Mock
	private OrganizationReconcileService organizationReconcileService;

	@Mock
	private OrganizationAsyncRunner organizationAsyncRunner;

	@Mock
	private OrganizationPlanStore organizationPlanStore;

	@Mock
	private ExecutionRepository executionRepository;

	private final OperationLockService operationLockService = new OperationLockService();
	private final OrganizationPathValidator organizationPathValidator = pathValidator();

	private OrganizationPathValidator pathValidator() {
		AppSettingService settings = mock(AppSettingService.class);
		WorkspaceManager workspace = mock(WorkspaceManager.class);

		when(workspace.getWorkspacePath()).thenReturn(Path.of("C:/"));

		return new OrganizationPathValidator(settings, workspace);
	}

	@Test
	void previewShouldRebuildMetadataWithDateFieldByDefault() {
		OrganizationPreviewRequest request = previewRequest(true, List.of());

		OrganizationPlan plan = plan();

		when(organizationPlanner.preview(request)).thenReturn(plan);

		Assertions.assertThat(service().preview(request)).isSameAs(plan);

		ArgumentCaptor<MetadataRebuildRequest> captor = ArgumentCaptor.forClass(MetadataRebuildRequest.class);

		verify(metadataRebuildService).rebuild(captor.capture());

		Assertions.assertThat(captor.getValue().refresh()).containsExactly(MetadataRebuildField.DATE);
		Assertions.assertThat(captor.getValue().dryRun()).isFalse();
	}

	@Test
	void executeShouldUseExplicitRebuildFieldsAndDelegateExecution() {
		OrganizationExecuteRequest request = new OrganizationExecuteRequest("C:/input", "C:/target", true,
				OrganizationLayout.DEFAULT, 50, true, List.of(MetadataRebuildField.GPS), null, null, null, null, null,
				false, false);

		OrganizationExecuteResponse response = new OrganizationExecuteResponse(1L, "FINISHED", LocalDateTime.now(),
				LocalDateTime.now(), "C:/input", "C:/target", 1, 1, 0, 0, false, "ok");

		when(organizationExecutor.execute(request)).thenReturn(response);

		Assertions.assertThat(service().execute(request)).isSameAs(response);

		ArgumentCaptor<MetadataRebuildRequest> captor = ArgumentCaptor.forClass(MetadataRebuildRequest.class);

		verify(metadataRebuildService).rebuild(captor.capture());

		Assertions.assertThat(captor.getValue().refresh()).containsExactly(MetadataRebuildField.GPS);
	}

	@Test
	void executeShouldRejectWhenSourcePathIsAlreadyLocked() {
		OrganizationExecuteRequest request = new OrganizationExecuteRequest("C:/input", "C:/target", true,
				OrganizationLayout.DEFAULT, 50, false, null, null, null, null, null, null, false, false);

		CountDownLatch lockAcquired = new CountDownLatch(1);
		CountDownLatch releaseLock = new CountDownLatch(1);

		Thread lockThread = holdLock(Path.of("C:/input"), lockAcquired, releaseLock);

		try {
			Assertions.assertThat(lockAcquired.await(2, TimeUnit.SECONDS)).isTrue();

			var response = service().execute(request);

			Assertions.assertThat(response.rejected()).isTrue();
			Assertions.assertThat(response.status()).isEqualTo("ERROR");
			Assertions.assertThat(response.message()).contains("Organization rejected");

			verify(organizationExecutor, never()).execute(any());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		} finally {
			releaseLock.countDown();
		}

		Assertions.assertThatCode(lockThread::join).doesNotThrowAnyException();
	}

	@Test
	void previewShouldRejectSamePathAndTargetInsideSource() {
		OrganizationService service = service();

		OrganizationPreviewRequest samePathRequest = new OrganizationPreviewRequest("C:/input", "C:/input", true,
				OrganizationLayout.DEFAULT, 50, false, null, null, null, null, null, null);
		OrganizationPreviewRequest nestedTargetRequest = new OrganizationPreviewRequest("C:/input",
				"C:/input/organized", true, OrganizationLayout.DEFAULT, 50, false, null, null, null, null, null, null);

		Assertions.assertThatThrownBy(() -> service.preview(samePathRequest))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("devem ser diferentes");

		Assertions.assertThatThrownBy(() -> service.preview(nestedTargetRequest))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dentro da pasta de origem");

		verify(organizationPlanner, never()).preview(any());
	}

	@Test
	void executeShouldRejectSamePathBeforeDelegatingExecution() {
		OrganizationExecuteRequest request = new OrganizationExecuteRequest("C:/input", "C:/input", true,
				OrganizationLayout.DEFAULT, 50, false, null, null, null, null, null, null, false, false);

		OrganizationService service = service();

		Assertions.assertThatThrownBy(() -> service.execute(request)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("devem ser diferentes");

		verify(organizationExecutor, never()).execute(any());
	}

	@Test
	void previewShouldSkipRebuildWhenNotRequested() {
		OrganizationPreviewRequest request = previewRequest(false, null);

		OrganizationPlan plan = plan();

		when(organizationPlanner.preview(request)).thenReturn(plan);

		Assertions.assertThat(service().preview(request)).isSameAs(plan);

		verify(metadataRebuildService, never()).rebuild(any());
	}

	@Test
	void reconcileShouldDelegateWithoutPathValidation() {
		OrganizationReconcileRequest request = new OrganizationReconcileRequest("C:/input", true, false, 10);

		OrganizationReconcileResponse response = new OrganizationReconcileResponse("C:/input", true, false, 1, 1, 0, 0,
				0, List.of(), List.of(), List.of(), 0, 0, 0);

		when(organizationReconcileService.reconcile(request)).thenReturn(response);

		Assertions.assertThat(service().reconcile(request)).isSameAs(response);
	}

	@Test
	void previewAsyncShouldSaveStartedExecutionAndDispatchToRunnerWithoutWaiting() {
		// Preview is a dry-run execute request now: same type as execute, dryRun=true.
		OrganizationExecuteRequest request = new OrganizationExecuteRequest("C:/input", "C:/target", true,
				OrganizationLayout.DEFAULT, 50, false, null, true, null, null, null, null, false, false,
				LocationSubdivision.NONE, null, LocationFallbackMode.IGNORE, true);

		when(executionRepository.save(any())).thenAnswer(invocation -> {
			Execution execution = invocation.getArgument(0);
			execution.setId(10L);
			return execution;
		});

		var response = serviceWithAsync().previewAsync(request);

		Assertions.assertThat(response.executionId()).isEqualTo(UuidV7.fromLegacy(10L));
		Assertions.assertThat(response.status()).isEqualTo(ExecutionStatus.STARTED.name());
		Assertions.assertThat(request.dryRunValue()).isTrue();

		verify(organizationAsyncRunner).runPreview(ArgumentMatchers.eq(request), any());
		verify(organizationExecutor, never()).execute(any());
	}

	@Test
	void executeAsyncShouldSaveStartedExecutionAndDispatchToRunnerWithoutWaiting() {
		OrganizationExecuteRequest request = new OrganizationExecuteRequest("C:/input", "C:/target", true,
				OrganizationLayout.DEFAULT, 50, false, null, null, null, null, null, null, false, false);

		when(executionRepository.save(any())).thenAnswer(invocation -> {
			Execution execution = invocation.getArgument(0);
			execution.setId(11L);
			return execution;
		});

		var response = serviceWithAsync().executeAsync(request);

		Assertions.assertThat(response.executionId()).isEqualTo(UuidV7.fromLegacy(11L));
		Assertions.assertThat(response.status()).isEqualTo(ExecutionStatus.STARTED.name());

		verify(organizationAsyncRunner).runExecute(ArgumentMatchers.eq(request), any());
		verify(organizationExecutor, never()).execute(any());
	}

	@Test
	void getPreviewPlanShouldReturnStoredPlan() {
		OrganizationPlan plan = plan();

		when(organizationPlanStore.get(5L)).thenReturn(plan);

		Assertions.assertThat(service().getPreviewPlan(5L)).isSameAs(plan);
	}

	/**
	 * The REST surface speaks public ids and the store speaks internal ones, so
	 * every public entry point has to translate before it delegates - getting that
	 * translation wrong is how a screen ends up looking at another execution.
	 */
	@Test
	void getPreviewPlanPublicShouldTranslateThePublicIdBeforeReadingTheStore() {
		UUID publicId = UUID.randomUUID();

		OrganizationPlan plan = plan();

		when(executionRepository.findByPublicId(publicId))
				.thenReturn(Optional.of(Execution.builder().id(9L).publicId(publicId).build()));
		when(organizationPlanStore.get(9L)).thenReturn(plan);

		Assertions.assertThat(service().getPreviewPlanPublic(publicId)).isSameAs(plan);
	}

	@Test
	void undoPublicShouldTranslateThePublicIdBeforeUndoing() {
		UUID publicId = UUID.randomUUID();

		OrganizationUndoResponse response = new OrganizationUndoResponse(4L, "FINISHED", 2, 2, 0, 0, "ok", List.of());

		when(executionRepository.findByPublicId(publicId))
				.thenReturn(Optional.of(Execution.builder().id(4L).publicId(publicId).build()));
		when(organizationUndoService.undo(4L)).thenReturn(response);

		Assertions.assertThat(service().undoPublic(publicId)).isSameAs(response);
	}

	/** An id nobody recognises fails saying which one, not with a null later on. */
	@Test
	void publicEntryPointsRefuseAnUnknownExecutionId() {
		UUID publicId = UUID.randomUUID();

		when(executionRepository.findByPublicId(publicId)).thenReturn(Optional.empty());

		OrganizationService service = service();

		Assertions.assertThatThrownBy(() -> service.undoPublic(publicId)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(publicId.toString());
	}

	private OrganizationService service() {
		return new OrganizationService(organizationPlanner, organizationExecutor, metadataRebuildService,
				operationLockService, organizationPathValidator, organizationUndoService, organizationReconcileService,
				organizationAsyncRunner, organizationPlanStore, executionRepository,
				new ExecutionMapper(new ExecutionMessageCodec(new ObjectMapper()), new ExecutionLabels()),
				Clock.systemDefaultZone());
	}

	private OrganizationService serviceWithAsync() {
		return service();
	}

	private Thread holdLock(Path path, CountDownLatch lockAcquired, CountDownLatch releaseLock) {
		Thread thread = new Thread(() -> {
			try (var _ = operationLockService.acquire(ExecutionType.INVENTORY, path)) {
				lockAcquired.countDown();
				releaseLock.await();
			} catch (InterruptedException _) {
				Thread.currentThread().interrupt();
			}
		});

		thread.start();

		return thread;
	}

	private OrganizationPreviewRequest previewRequest(boolean rebuildMetadata, List<MetadataRebuildField> rebuild) {
		return new OrganizationPreviewRequest("C:/input", "C:/target", true, OrganizationLayout.DEFAULT, 50,
				rebuildMetadata, rebuild, null, null, null, null, null);
	}

	private OrganizationPlan plan() {
		return new OrganizationPlan("C:/input", "C:/target", OrganizationLayout.DEFAULT, false,
				new OrganizationSummary(0, 0, 0, 0, 0, 0, 0, 0, 0), List.of());
	}
}