package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Rebuilding a reconcile from the row that asked for it.
 *
 * <p>
 * Like the inventory, RECONCILE carries no request payload - the folder and
 * whether to recurse are columns - which is what makes it one of the types a
 * worker can run knowing nothing but the row.
 */
class ReconcileJobHandlerTest {

	private final OrganizationReconcileApply organizationReconcileApply = mock(OrganizationReconcileApply.class);

	private final ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);

	private final ReconcileJobHandler handler = new ReconcileJobHandler(organizationReconcileApply,
			executionProgressService);

	private final ExecutionOwnership ownership = Takings.owning(1L);

	/**
	 * A reconcile only compares the tree with the catalog and corrects what
	 * disagrees, so running it twice is indistinguishable from running it once -
	 * which is why an abandoned one goes back on the queue instead of ending as
	 * interrupted like every writer does.
	 */
	@Test
	void isSimplyRunAgainWhenItWasAbandonedHalfway() {
		assertThat(handler.resumable()).isTrue();
	}

	@Test
	void answersForReconcileExecutions() {
		assertThat(handler.type()).isEqualTo(ExecutionType.RECONCILE);
	}

	@Test
	void reconcilesTheFolderTheRowNames(@TempDir Path folder) {
		when(organizationReconcileApply.reconcileAndApply(any())).thenReturn(response(0, 0));

		Execution execution = Execution.builder().executionType(ExecutionType.RECONCILE)
				.status(ExecutionStatus.RUNNING).recursive(true).build();

		handler.handle(execution,
				new ClaimedExecution(1L, ExecutionType.RECONCILE.name(), folder.toString(), null, null), null);

		ArgumentCaptor<OrganizationReconcileRequest> request = ArgumentCaptor.captor();

		verify(organizationReconcileApply).reconcileAndApply(request.capture());

		assertThat(request.getValue().sourcePath()).isEqualTo(folder.toString());
		assertThat(request.getValue().recursive()).isTrue();
	}

	/**
	 * The two numbers a reconcile can honestly report: what the walk found, and
	 * how many catalog entries it corrected. Nothing goes into filesMoved, because
	 * a reconcile moves no file.
	 */
	@Test
	void recordsWhatTheWalkFoundAndWhatItRepaired(@TempDir Path folder) {
		when(organizationReconcileApply.reconcileAndApply(any())).thenReturn(response(120, 3));

		Execution execution = execution();

		handler.handle(execution, claimed(folder), ownership);

		verify(executionProgressService).finishReconcile(eq(ownership), eq(120), eq(3), any());
	}

	@Test
	void recordsZeroRepairsWhenThePassFoundNothingToDo(@TempDir Path folder) {
		when(organizationReconcileApply.reconcileAndApply(any())).thenReturn(response(120, 0));

		handler.handle(execution(), claimed(folder), null);

		verify(executionProgressService).finishReconcile(any(), eq(120), eq(0), any());
	}

	private OrganizationReconcileResponse response(long filesOnDisk, long repairedItems) {
		return new OrganizationReconcileResponse("D:\fotos", true, false, filesOnDisk, filesOnDisk, 0, 0,
				List.of(), List.of(), 0, 0, 0, repairedItems);
	}

	private ClaimedExecution claimed(Path folder) {
		return new ClaimedExecution(1L, ExecutionType.RECONCILE.name(), folder.toString(), null, null);
	}

	private Execution execution() {
		return Execution.builder().executionType(ExecutionType.RECONCILE).status(ExecutionStatus.RUNNING)
				.recursive(true).build();
	}
}