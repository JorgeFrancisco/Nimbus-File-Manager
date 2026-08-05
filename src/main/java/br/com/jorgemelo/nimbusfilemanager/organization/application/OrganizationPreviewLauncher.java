package br.com.jorgemelo.nimbusfilemanager.organization.application;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.constants.OrganizationConstants;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecutePayload;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Asking for a preview to be built.
 *
 * <p>
 * The row goes in PENDING and a worker picks it up, exactly like the run it
 * describes. What that buys is not speed - the preview was already off the
 * request thread - but the thing this slice exists for: the application stops
 * composing the class that can move files in order to find out what moving them
 * would do.
 *
 * <p>
 * Previews are not deduplicated, for the same reason organizations are not: two
 * requests over the same folders are two questions a person asked, and the
 * second may carry a different layout or a different limit. What keeps them from
 * running at once is the per-type limit in the worker and the path locks.
 */
@Service
public class OrganizationPreviewLauncher {

	private final ExecutionEnqueueService executionEnqueueService;
	private final ExecutionPayloadCodec executionPayloadCodec;
	private final ExecutionMapper executionMapper;

	public OrganizationPreviewLauncher(ExecutionEnqueueService executionEnqueueService,
			ExecutionPayloadCodec executionPayloadCodec, ExecutionMapper executionMapper) {
		this.executionEnqueueService = executionEnqueueService;
		this.executionPayloadCodec = executionPayloadCodec;
		this.executionMapper = executionMapper;
	}

	public ExecutionResponse launch(OrganizationExecuteRequest request) {
		Execution queued = Execution.builder().executionType(ExecutionType.ORGANIZATION_PREVIEW)
				.sourcePath(PathUtils.normalize(request.source())).targetPath(PathUtils.normalize(request.target()))
				.recursive(request.recursiveValue()).executeFlag(false)
				.requestPayload(executionPayloadCodec.encode(payloadOf(request)))
				.statusMessage(StatusMessage.code(ExecutionMessages.PREVIEW_STARTED)).build();

		return executionMapper.toResponse(executionEnqueueService.enqueue(queued)
				.orElseThrow(() -> new IllegalStateException("A preview was refused as a duplicate, and previews carry "
						+ "no deduplication key - so the row was refused for a reason nobody has described yet")));
	}

	private OrganizationExecutePayload payloadOf(OrganizationExecuteRequest request) {
		return new OrganizationExecutePayload(OrganizationConstants.EXECUTE_PAYLOAD_SCHEMA_VERSION, request.layout(),
				request.limit(), request.rebuildMetadata(), request.rebuild(), request.skipAlreadyOrganized(),
				request.onlyCategories(), request.onlySubcategories(), request.onlyExtensions(),
				request.onlyFileTypes(), request.allowConflicts(), request.overwriteExisting(),
				request.locationSubdivision(), request.locationMinConfidence(), request.locationFallback());
	}
}