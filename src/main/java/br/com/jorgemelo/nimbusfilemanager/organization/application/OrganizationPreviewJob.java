package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPlan;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import lombok.extern.slf4j.Slf4j;

/**
 * Building a preview, and leaving it where anybody can read it.
 *
 * <p>
 * The order is the whole protocol. The plan is written the moment it exists, in
 * a state nothing reads; the dry run then walks every item, applying the same
 * read-only checks a real run applies, so the counts the user sees are the
 * counts a run would produce; only when that finishes is the plan published, in
 * one update. A preview that dies anywhere in between leaves rows nobody can
 * see, which the expiry sweeps like any other.
 *
 * <p>
 * Nothing here writes to the library - the dry run's every side effect is
 * blocked inside the executor. The ownership still travels with it, because the
 * execution row is written all the same: the total, the phase and the outcome
 * are writes about a taking, and a preview that lost its turn must not report on
 * the row that replaced it.
 */
@Slf4j
@Component
class OrganizationPreviewJob {

	private final OrganizationExecutor organizationExecutor;
	private final OrganizationPlanWriter organizationPlanWriter;

	OrganizationPreviewJob(OrganizationExecutor organizationExecutor, OrganizationPlanWriter organizationPlanWriter) {
		this.organizationExecutor = organizationExecutor;
		this.organizationPlanWriter = organizationPlanWriter;
	}

	void run(OrganizationExecuteRequest request, Execution execution, ExecutionOwnership ownership) {
		Long executionId = execution.getId();

		AtomicReference<OrganizationPlan> planned = new AtomicReference<>();

		try {
			organizationExecutor.execute(request, execution, ownership, plan -> {
				planned.set(plan);

				organizationPlanWriter.build(executionId, plan);
			});
		} catch (RuntimeException exception) {
			markFailed(executionId, planned.get());

			throw exception;
		}

		OrganizationPlan plan = planned.get();

		if (plan == null) {
			// The executor returned without ever planning, which is what a rejection
			// before the planning step looks like. There is nothing to publish and
			// nothing was written.
			return;
		}

		if (!organizationPlanWriter.publish(executionId, plan)) {
			log.warn("Organization preview {} finished but its plan was not published", executionId);
		}
	}

	private void markFailed(Long executionId, OrganizationPlan planned) {
		if (planned == null) {
			return;
		}

		organizationPlanWriter.markFailed(executionId);
	}
}