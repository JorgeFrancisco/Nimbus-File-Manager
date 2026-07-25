package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * Records a reconciliation as a distinct RECONCILE execution, but only when it
 * actually repaired the catalog (a rename, a stale-path fix or a missing-mark).
 * A silent no-op reconcile leaves no row - the watcher keeps a lightweight
 * in-memory heartbeat for those. This makes reconciliation visible and
 * distinguishable from inventory in the history without flooding it with rows
 * for the common "nothing changed" case.
 */
@Component
public class ReconcileExecutionRecorder {

	private final ExecutionRepository executionRepository;
	private final ExecutionProgressService executionProgressService;
	private final Clock clock;
	private final String applicationVersion;

	public ReconcileExecutionRecorder(ExecutionRepository executionRepository,
			ExecutionProgressService executionProgressService, Clock clock,
			@Value("${application.version}") String applicationVersion) {
		this.executionRepository = executionRepository;
		this.executionProgressService = executionProgressService;
		this.clock = clock;
		this.applicationVersion = applicationVersion;
	}

	public void recordIfRepaired(ExecutionTrigger trigger, Path source, OrganizationReconcileResponse response) {
		long total = response.renamed() + response.repairedPaths() + response.markedMissing();

		if (total == 0) {
			return;
		}

		Execution startedExecution = Execution.builder().executionType(ExecutionType.RECONCILE)
				.status(ExecutionStatus.STARTED).triggerEvent(trigger).startedAt(LocalDateTime.now(clock))
				.sourcePath(source.toString()).recursive(response.recursive()).executeFlag(true)
				.applicationVersion(applicationVersion)
				.statusMessage(StatusMessage.code(ExecutionMessages.RECONCILE_REPAIRED)).build();

		Execution execution = executionRepository.save(startedExecution);

		executionProgressService.finish(execution, ExecutionStatus.FINISHED, 0, 0, 0, 0, ExecutionMessages
				.reconcileRepaired(response.renamed(), response.repairedPaths(), response.markedMissing()));
	}
}