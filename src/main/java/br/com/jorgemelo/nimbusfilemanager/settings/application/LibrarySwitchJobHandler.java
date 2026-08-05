package br.com.jorgemelo.nimbusfilemanager.settings.application;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsMessages;
import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.LibrarySwitchPayload;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import lombok.extern.slf4j.Slf4j;

/**
 * Carries out a library switch that came off the queue.
 *
 * <p>
 * The order is the contract: the old catalog is forgotten <em>before</em> the
 * setting names the new library. Reversed, there would be a window in which the
 * screens showed one library's files under another's name.
 *
 * <p>
 * Which library gets forgotten comes from the row this worker claimed and locked
 * - never from the setting. That is what makes a switch claimed late still mean
 * what it meant when somebody asked for it: a row that says "forget A, adopt B"
 * forgets A even if the library has since become C.
 *
 * <p>
 * Resumable, because every step is idempotent: forgetting a library that is
 * already forgotten deletes nothing, emptying an empty cache does nothing, and
 * writing a setting that already holds the value writes the same value. A second
 * attempt from the start is indistinguishable from a first, which is exactly
 * what a switch interrupted halfway needs.
 */
@Slf4j
@Component
public class LibrarySwitchJobHandler implements ExecutionJobHandler {

	private final LibraryCatalogCleanupService libraryCatalogCleanupService;
	private final AppSettingService appSettingService;
	private final ExecutionProgressService executionProgressService;
	private final ExecutionPayloadCodec executionPayloadCodec;

	public LibrarySwitchJobHandler(LibraryCatalogCleanupService libraryCatalogCleanupService,
			AppSettingService appSettingService, ExecutionProgressService executionProgressService,
			ExecutionPayloadCodec executionPayloadCodec) {
		this.libraryCatalogCleanupService = libraryCatalogCleanupService;
		this.appSettingService = appSettingService;
		this.executionProgressService = executionProgressService;
		this.executionPayloadCodec = executionPayloadCodec;
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.LIBRARY_SWITCH;
	}

	@Override
	public boolean resumable() {
		return true;
	}

	/**
	 * The ownership goes unused, and deliberately: this touches no file of the
	 * user's. What it needs from the dispatcher is the lock over both trees, which
	 * is taken before this method is called and is what keeps an organization or an
	 * inventory from running against a library being replaced.
	 */
	@Override
	public void handle(Execution execution, ClaimedExecution claimed, ExecutionOwnership ownership) {
		LibrarySwitchPayload payload = payloadOf(claimed);

		String forgotten = claimed.sourcePath();
		String adopted = claimed.targetPath();

		int removed = forgotten == null || forgotten.isBlank() ? 0 : libraryCatalogCleanupService.clear(forgotten);

		appSettingService.update(SettingsConstants.WATCH_FOLDER, adopted, payload.username());

		log.info("Library switched from {} to {}. Catalog entries removed={}", forgotten, adopted, removed);

		executionProgressService.finishCommand(execution, ExecutionStatus.FINISHED,
				new ExecutionCounts(removed, removed, 0, 0), SettingsMessages.librarySwitched(removed));
	}

	/**
	 * A payload written in a shape this version does not know is refused rather
	 * than read as far as it goes. The folders are not in it - they are the row's
	 * own columns, which is what was locked - so what is checked here is only that
	 * the rest was written by a version that meant the same thing.
	 */
	private LibrarySwitchPayload payloadOf(ClaimedExecution claimed) {
		LibrarySwitchPayload payload = executionPayloadCodec.decode(claimed.requestPayload(),
				LibrarySwitchPayload.class);

		if (payload.schemaVersion() == null
				|| payload.schemaVersion() != SettingsConstants.LIBRARY_SWITCH_PAYLOAD_SCHEMA_VERSION) {
			throw new IllegalArgumentException("Library switch payload schema " + payload.schemaVersion()
					+ " cannot be run by this version, which writes and reads schema "
					+ SettingsConstants.LIBRARY_SWITCH_PAYLOAD_SCHEMA_VERSION);
		}

		return payload;
	}
}