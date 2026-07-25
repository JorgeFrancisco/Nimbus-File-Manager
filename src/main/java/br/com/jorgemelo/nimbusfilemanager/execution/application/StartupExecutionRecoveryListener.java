package br.com.jorgemelo.nimbusfilemanager.execution.application;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * If the app is restarted (or crashes) while an inventory/organization run is
 * in progress, that execution's row is left behind still marked
 * STARTED/SCANNING_FILES/PROCESSING_FILES forever - there's no way to actually
 * resume a scan mid-way (no cursor is persisted), so the honest thing to do is
 * flag it. Previously this only happened lazily, right before the next
 * inventory run started; that left the row stuck (and the progress screen
 * polling forever if reopened) for as long as the user didn't happen to start
 * another inventory. Running it once here, as soon as the app is back up, means
 * the user sees "INTERRUPTED" on their next visit instead of a scan that looks
 * like it's silently still running.
 */
@Slf4j
@Component
public class StartupExecutionRecoveryListener implements ApplicationListener<ApplicationReadyEvent> {

	private final ExecutionProgressService executionProgressService;

	public StartupExecutionRecoveryListener(ExecutionProgressService executionProgressService) {
		this.executionProgressService = executionProgressService;
	}

	@Override
	public void onApplicationEvent(ApplicationReadyEvent event) {
		log.info("Marking any execution left running from a previous shutdown as INTERRUPTED.");

		executionProgressService.markInterruptedExecutions();
	}
}