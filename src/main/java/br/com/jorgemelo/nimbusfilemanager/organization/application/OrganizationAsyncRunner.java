package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhaseType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.AsyncConfig;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.PerformanceTelemetryService;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.PhaseSnapshot;

/**
 * Runs organization preview/execute in the background so the web request that
 * triggered it can return immediately with an executionId to poll. Must live in
 * its own bean (not inside OrganizationService) so the {@code @Async} proxy is
 * honored - self-invocation would run synchronously and defeat the purpose.
 *
 * <p>
 * Preview and execute are the <em>same</em> flow: both call
 * {@link OrganizationExecutor#execute(OrganizationExecuteRequest, Execution)},
 * differing only by the request's {@code dryRun} flag. Preview passes
 * {@code dryRun=true}, so the executor runs every read-only check and reports
 * the per-item outcome exactly as execute would, but blocks all side effects.
 * The two entry points exist only to tag the run with the right telemetry
 * phase.
 */
@Service
public class OrganizationAsyncRunner {

	private final OrganizationExecutor organizationExecutor;
	private final PerformanceTelemetryService performanceTelemetryService;

	public OrganizationAsyncRunner(OrganizationExecutor organizationExecutor,
			PerformanceTelemetryService performanceTelemetryService) {
		this.organizationExecutor = organizationExecutor;
		this.performanceTelemetryService = performanceTelemetryService;
	}

	@Async(AsyncConfig.TASK_EXECUTOR)
	public void runPreview(OrganizationExecuteRequest request, Execution execution) {
		// Dry-run of execute (request.dryRun=true): the executor owns the lock, plan
		// storage, progress and finish/reject lifecycle - nothing to do here but time
		// it.
		long start = System.nanoTime();

		try {
			organizationExecutor.execute(request, execution);
		} finally {
			recordPhase(execution.getId(), ExecutionPhaseType.PLAN, start, 0);
		}
	}

	@Async(AsyncConfig.TASK_EXECUTOR)
	public void runExecute(OrganizationExecuteRequest request, Execution execution) {
		// OrganizationExecutor.execute(request, execution) already handles its own
		// lock, progress and finish/fail lifecycle for the given execution.
		long start = System.nanoTime();

		try {
			organizationExecutor.execute(request, execution);
		} finally {
			recordPhase(execution.getId(), ExecutionPhaseType.MOVEMENT, start, 0);
		}
	}

	/**
	 * Persists a single organization phase duration. filesPerSecond is derived by
	 * the telemetry service from the execution's own filesFound/filesMoved, so a
	 * zero {@code items} here is fine - it only feeds the per-phase throughput.
	 * Telemetry must never influence the organization outcome, so any failure is
	 * swallowed.
	 */
	private void recordPhase(Long executionId, ExecutionPhaseType phase, long startNanos, long items) {
		try {
			long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

			performanceTelemetryService.recordMetrics(executionId, null, Map.of(phase, new PhaseSnapshot(millis,
					items)));
		} catch (RuntimeException _) {
			// Best-effort telemetry: never break the run because a phase failed to record.
		}
	}
}