package br.com.jorgemelo.nimbusfilemanager.settings.application;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsMessages;
import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.LibrarySwitchPayload;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * Asking for the monitored library to be replaced.
 *
 * <p>
 * The switch used to be a thread in this process holding a database session
 * open: it stopped every worker in every process, cancelled what was running,
 * deleted the old catalog, wrote the setting and reconfigured the watcher. If
 * the application died in the middle, the catalog was gone and the setting still
 * named the library it belonged to, and nothing ever finished the job.
 *
 * <p>
 * It is a row now. What stays here is what the queue cannot do: refusing a
 * folder that is not a folder while somebody is looking at the screen, and
 * asking whatever is running to stop. What goes is the work itself.
 */
@Slf4j
@Service
public class LibrarySwitchLauncher extends LocalizedComponent {

	private final ExecutionEnqueueService executionEnqueueService;
	private final ExecutionCancellationService executionCancellationService;
	private final ExecutionPayloadCodec executionPayloadCodec;
	private final ExecutionMapper executionMapper;

	public LibrarySwitchLauncher(ExecutionEnqueueService executionEnqueueService,
			ExecutionCancellationService executionCancellationService, ExecutionPayloadCodec executionPayloadCodec,
			ExecutionMapper executionMapper) {
		this.executionEnqueueService = executionEnqueueService;
		this.executionCancellationService = executionCancellationService;
		this.executionPayloadCodec = executionPayloadCodec;
		this.executionMapper = executionMapper;
	}

	public void validateNewFolder(String newFolder) {
		if (newFolder == null || newFolder.isBlank() || !Files.isDirectory(Path.of(newFolder))) {
			throw new IllegalArgumentException(message("backend.folder.newInvalid"));
		}
	}

	/**
	 * Queues the switch and returns at once.
	 *
	 * <p>
	 * Cancellation is requested first and enqueueing second, so the switch is not
	 * asking itself to stop. What that request does is durable - it marks the rows -
	 * and what makes the switch actually wait for those runs to let go is the path
	 * lock every execution takes over its own folders: a worker that cannot have
	 * both trees hands the row back to the queue, with its attempts untouched,
	 * until whoever holds them is done. No global standstill and no polling loop.
	 */
	public ExecutionResponse launch(String oldFolder, String newFolder, String username) {
		executionCancellationService.requestAllCancellations();

		Execution queued = Execution.builder().executionType(ExecutionType.LIBRARY_SWITCH)
				.sourcePath(oldFolder == null || oldFolder.isBlank() ? null : PathUtils.normalize(oldFolder))
				.targetPath(PathUtils.normalize(newFolder)).recursive(true).executeFlag(true)
				.dedupKey(dedupKey(oldFolder, newFolder))
				.requestPayload(executionPayloadCodec
						.encode(new LibrarySwitchPayload(SettingsConstants.LIBRARY_SWITCH_PAYLOAD_SCHEMA_VERSION,
								username)))
				.statusMessage(StatusMessage.code(SettingsMessages.LIBRARY_SWITCH_STARTED)).build();

		Execution existing = executionEnqueueService.enqueueOrExisting(queued);

		log.info("Library switch from {} to {} queued as execution {}", oldFolder, newFolder,
				existing.getExecutionPublicId());

		return executionMapper.toResponse(existing);
	}

	/**
	 * The same switch asked for twice is one switch.
	 *
	 * <p>
	 * A double click, a retried POST or two open tabs all describe the identical
	 * intention - replace this library with that one - and the second must not
	 * become a second run that forgets an already forgotten catalog and writes an
	 * already written setting. The key names both ends because "switch to B" from A
	 * and from C are different intentions.
	 */
	private String dedupKey(String oldFolder, String newFolder) {
		return (oldFolder == null || oldFolder.isBlank() ? "" : PathUtils.normalize(oldFolder)) + "->"
				+ PathUtils.normalize(newFolder);
	}
}