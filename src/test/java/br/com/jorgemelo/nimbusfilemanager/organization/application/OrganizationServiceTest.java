package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationSummary;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.StoredPlanPage;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;

/**
 * What is left of this class after the preview stopped running here.
 *
 * <p>
 * It validates folders, queues, and reads. It no longer computes a plan, holds
 * one, writes an execution row of its own or reaches - through anything - the
 * class that can move the user's files, which is what took its name off the
 * mutation-port exception list.
 */
@ExtendWith(MockitoExtension.class)
class OrganizationServiceTest {

	@Mock
	private OrganizationLauncherService organizationLauncherService;

	@Mock
	private OrganizationPreviewLauncher organizationPreviewLauncher;

	@Mock
	private OrganizationPlanReader organizationPlanReader;

	@Mock
	private OrganizationUndoLauncherService organizationUndoLauncherService;

	@Mock
	private OrganizationReconcileService organizationReconcileService;

	@Mock
	private ExecutionRepository executionRepository;

	private final OrganizationPathValidator organizationPathValidator = pathValidator();

	private OrganizationPathValidator pathValidator() {
		AppSettingService settings = mock(AppSettingService.class);
		WorkspaceManager workspace = mock(WorkspaceManager.class);

		when(workspace.getWorkspacePath()).thenReturn(Path.of("C:/"));

		return new OrganizationPathValidator(settings, workspace);
	}

	@Test
	void previewIsQueuedAndNothingIsComputedHere() {
		OrganizationExecuteRequest request = request("C:/input", "C:/target");

		ExecutionResponse queued = response("ORGANIZATION_PREVIEW");

		when(organizationPreviewLauncher.launch(request)).thenReturn(queued);

		Assertions.assertThat(service().previewAsync(request)).isSameAs(queued);

		// No row written in this process, and nothing read back afterwards: the plan
		// belongs to the worker that will build it.
		verify(executionRepository, never()).save(any());
		verifyNoInteractions(organizationPlanReader);
	}

	/**
	 * The folders are still checked here, before anything is queued. A request that
	 * cannot be right is refused while somebody is looking at the screen, instead of
	 * becoming a row that fails in another process minutes later.
	 */
	@Test
	void aPreviewOverImpossibleFoldersIsRefusedBeforeItIsQueued() {
		OrganizationService service = service();

		OrganizationExecuteRequest samePath = request("C:/input", "C:/input");
		OrganizationExecuteRequest nested = request("C:/input", "C:/input/organized");

		Assertions.assertThatThrownBy(() -> service.previewAsync(samePath))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("devem ser diferentes");
		Assertions.assertThatThrownBy(() -> service.previewAsync(nested)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("dentro da pasta de origem");

		verify(organizationPreviewLauncher, never()).launch(any());
	}

	@Test
	void anExecuteOverImpossibleFoldersIsRefusedBeforeItIsQueued() {
		OrganizationService service = service();

		OrganizationExecuteRequest samePath = request("C:/input", "C:/input");

		Assertions.assertThatThrownBy(() -> service.executeAsync(samePath))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("devem ser diferentes");

		verify(organizationLauncherService, never()).launch(any());
	}

	/**
	 * The run is queued and nothing is started here - no row written in this
	 * process, no background thread, no executor. It also does not read the plan:
	 * the run recalculates, which is the recorded contract rather than an
	 * oversight.
	 */
	@Test
	void executeIsQueuedAndDoesNotReadThePlan() {
		OrganizationExecuteRequest request = request("C:/input", "C:/target");

		ExecutionResponse queued = response("ORGANIZATION");

		when(organizationLauncherService.launch(request)).thenReturn(queued);

		Assertions.assertThat(service().executeAsync(request)).isSameAs(queued);

		verify(executionRepository, never()).save(any());
		verifyNoInteractions(organizationPlanReader);
	}

	@Test
	void aPlanIsReadFromTheDatabaseByInternalId() {
		StoredPlanPage page = page();

		when(organizationPlanReader.page(5L, 0, 50, false)).thenReturn(Optional.of(page));

		Assertions.assertThat(service().planPage(5L, 0, 50, false)).containsSame(page);
	}

	/**
	 * The REST surface speaks public ids and the rows speak internal ones, so every
	 * public entry point translates before it delegates - getting that translation
	 * wrong is how a screen ends up looking at another execution's plan.
	 */
	@Test
	void aPublicIdIsTranslatedBeforeThePlanIsRead() {
		UUID publicId = UUID.randomUUID();

		StoredPlanPage page = page();

		when(executionRepository.findByExecutionPublicId(publicId))
				.thenReturn(Optional.of(Execution.builder().id(9L).executionPublicId(publicId).build()));
		when(organizationPlanReader.page(9L, 1, 20, true)).thenReturn(Optional.of(page));

		Assertions.assertThat(service().planPagePublic(publicId, 1, 20, true)).containsSame(page);
	}

	/**
	 * An execution nobody recognises reads as "no plan" rather than throwing. It is
	 * the same answer the screen already gives for a plan that expired, and the
	 * user can act on neither difference.
	 */
	@Test
	void anUnknownExecutionHasNoPlan() {
		UUID publicId = UUID.randomUUID();

		when(executionRepository.findByExecutionPublicId(publicId)).thenReturn(Optional.empty());

		Assertions.assertThat(service().planPagePublic(publicId, 0, 50, false)).isEmpty();

		verify(organizationPlanReader, never()).page(anyLong(), anyInt(), anyInt(), anyBoolean());
	}

	@Test
	void undoTranslatesThePublicIdBeforeQueueingTheReversal() {
		UUID publicId = UUID.randomUUID();

		Execution undone = Execution.builder().id(4L).executionPublicId(publicId).build();

		ExecutionResponse queued = response("UNDO");

		when(executionRepository.findByExecutionPublicId(publicId)).thenReturn(Optional.of(undone));
		when(organizationUndoLauncherService.launch(undone)).thenReturn(queued);

		Assertions.assertThat(service().undoPublic(publicId)).isSameAs(queued);
	}

	@Test
	void undoRefusesAnUnknownExecutionIdSayingWhichOne() {
		UUID publicId = UUID.randomUUID();

		when(executionRepository.findByExecutionPublicId(publicId)).thenReturn(Optional.empty());

		OrganizationService service = service();

		Assertions.assertThatThrownBy(() -> service.undoPublic(publicId)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(publicId.toString());
	}

	@Test
	void reconcileDelegatesWithoutPathValidation() {
		OrganizationReconcileRequest request = new OrganizationReconcileRequest("C:/input", true, false, 10);

		OrganizationReconcileResponse response = new OrganizationReconcileResponse("C:/input", true, false, 1, 1, 0, 0,
				List.of(), List.of(), 0, 0, 0, 0);

		when(organizationReconcileService.reconcile(request)).thenReturn(response);

		Assertions.assertThat(service().reconcile(request)).isSameAs(response);
	}

	private OrganizationService service() {
		return new OrganizationService(organizationPathValidator, organizationUndoLauncherService,
				organizationReconcileService, organizationLauncherService, organizationPreviewLauncher,
				organizationPlanReader, executionRepository);
	}

	private OrganizationExecuteRequest request(String source, String target) {
		return new OrganizationExecuteRequest(source, target, true, OrganizationLayout.DEFAULT, 50, false, null, null,
				null, null, null, null, false, false);
	}

	private ExecutionResponse response(String type) {
		return new ExecutionResponse(UUID.randomUUID(), type, ExecutionStatus.PENDING.name(), null, null, "C:/input",
				"C:/target", 0, 0, 0, 0, 0, 0, null, null, null, true);
	}

	private StoredPlanPage page() {
		return new StoredPlanPage("C:/input", "C:/target", OrganizationLayout.DEFAULT,
				new OrganizationSummary(0, 0, 0, 0, 0, 0, 0, 0, 0), false, List.of(), 0, 50, 0);
	}
}