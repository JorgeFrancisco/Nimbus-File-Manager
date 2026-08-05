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
 * Asking for an organization to be run.
 *
 * <p>
 * Nothing is started here. The row goes in PENDING with the folders in its own
 * columns and everything else in its payload, and a worker claims it - which is
 * what moved hours of file moving out of the process the user is looking at,
 * and what lets the run outlive that process entirely.
 *
 * <p>
 * Organizations are never deduplicated, by decision: two requests over the same
 * folders are two things a person asked for, and the second is not a repeat of
 * the first - it may carry a different layout, a different limit or a different
 * answer about conflicts. What stops them running at the same time is the
 * per-type limit in the worker and the path locks, not a unique index.
 */
@Service
public class OrganizationLauncherService {

	private final ExecutionEnqueueService executionEnqueueService;
	private final ExecutionPayloadCodec executionPayloadCodec;
	private final ExecutionMapper executionMapper;

	public OrganizationLauncherService(ExecutionEnqueueService executionEnqueueService,
			ExecutionPayloadCodec executionPayloadCodec, ExecutionMapper executionMapper) {
		this.executionEnqueueService = executionEnqueueService;
		this.executionPayloadCodec = executionPayloadCodec;
		this.executionMapper = executionMapper;
	}

	public ExecutionResponse launch(OrganizationExecuteRequest request) {
		Execution queued = Execution.builder().executionType(ExecutionType.ORGANIZATION)
				.sourcePath(PathUtils.normalize(request.source())).targetPath(PathUtils.normalize(request.target()))
				.recursive(request.recursiveValue()).executeFlag(true)
				.requestPayload(executionPayloadCodec.encode(payloadOf(request)))
				.statusMessage(StatusMessage.code(ExecutionMessages.ORGANIZATION_STARTED)).build();

		return executionMapper.toResponse(executionEnqueueService.enqueue(queued).orElseThrow(
				() -> new IllegalStateException("An organization was refused as a duplicate, and organizations carry "
						+ "no deduplication key - so the row was refused for a reason nobody has described yet")));
	}

	/**
	 * Everything the columns cannot hold. The folders are deliberately absent: they
	 * are columns, the worker locks what the columns say, and a second copy could
	 * only ever disagree.
	 */
	private OrganizationExecutePayload payloadOf(OrganizationExecuteRequest request) {
		return new OrganizationExecutePayload(OrganizationConstants.EXECUTE_PAYLOAD_SCHEMA_VERSION, request.layout(),
				request.limit(), request.rebuildMetadata(), request.rebuild(), request.skipAlreadyOrganized(),
				request.onlyCategories(), request.onlySubcategories(), request.onlyExtensions(),
				request.onlyFileTypes(), request.allowConflicts(), request.overwriteExisting(),
				request.locationSubdivision(), request.locationMinConfidence(), request.locationFallback());
	}
}