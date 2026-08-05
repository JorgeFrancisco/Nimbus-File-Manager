package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateMessages;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateDeletePayload;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.settings.application.QuarantineFolderPolicy;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Asking for duplicates to be sent to quarantine.
 *
 * <p>
 * Whether there is a quarantine to send them to is decided here, while somebody
 * is looking at the screen: a request that cannot be right should be refused
 * with a message rather than become a row that fails in another process.
 *
 * <p>
 * The quarantine root goes in both path columns because it is the one tree
 * every file in the batch is going to, and it is what the worker locks before
 * it starts. The files themselves are locked by the deletion once it can read
 * them - they are scattered across the library and no column could name them.
 */
@Service
public class DuplicateDeletionLauncherService extends LocalizedComponent {

	private final QuarantineFolderPolicy quarantineFolderPolicy;
	private final ExecutionEnqueueService executionEnqueueService;
	private final ExecutionPayloadCodec executionPayloadCodec;
	private final ExecutionMapper executionMapper;

	public DuplicateDeletionLauncherService(QuarantineFolderPolicy quarantineFolderPolicy,
			ExecutionEnqueueService executionEnqueueService, ExecutionPayloadCodec executionPayloadCodec,
			ExecutionMapper executionMapper) {
		this.quarantineFolderPolicy = quarantineFolderPolicy;
		this.executionEnqueueService = executionEnqueueService;
		this.executionPayloadCodec = executionPayloadCodec;
		this.executionMapper = executionMapper;
	}

	public ExecutionResponse launch(List<UUID> publicIds) {
		if (publicIds == null || publicIds.isEmpty()) {
			throw new IllegalArgumentException(message("backend.quarantine.noneSelected"));
		}

		Optional<Path> quarantineRoot = quarantineFolderPolicy.root();

		if (quarantineRoot.isEmpty()) {
			throw new IllegalArgumentException(message("backend.duplicates.quarantineNotConfigured"));
		}

		String root = PathUtils.normalize(quarantineRoot.get());

		Execution queued = Execution.builder().executionType(ExecutionType.DEDUP_DELETE).sourcePath(root)
				.targetPath(root).recursive(false).executeFlag(true).filesFound(publicIds.size())
				.requestPayload(executionPayloadCodec.encode(new DuplicateDeletePayload(
						DuplicateConstants.DELETE_PAYLOAD_SCHEMA_VERSION, List.copyOf(publicIds))))
				.statusMessage(StatusMessage.code(DuplicateMessages.deletionStarted().code())).build();

		return executionMapper.toResponse(executionEnqueueService.enqueue(queued)
				.orElseThrow(() -> new IllegalStateException("A duplicate deletion was refused as a duplicate "
						+ "request, and these carry no deduplication key - so it was refused for a reason "
						+ "nobody has described")));
	}
}