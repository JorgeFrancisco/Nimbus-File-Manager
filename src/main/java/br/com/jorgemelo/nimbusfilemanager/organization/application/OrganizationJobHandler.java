package br.com.jorgemelo.nimbusfilemanager.organization.application;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.organization.application.constants.OrganizationConstants;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecutePayload;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Runs an organization that came off the queue.
 *
 * <p>
 * The folders come from the row - they are what the dispatcher locked, so
 * reading them from anywhere else would mean working on paths this worker does
 * not hold. Everything the row cannot say arrives in the payload.
 *
 * <p>
 * It does not answer that it is resumable, and the default answer is the reason
 * why: this moves the user's files, so half of it has already happened by the
 * time anybody notices it stopped. A second run would start from a library the
 * first one had already rearranged, and reconciliation - not repetition - is
 * what closes that gap.
 */
@Component
public class OrganizationJobHandler implements ExecutionJobHandler {

	private final OrganizationExecutor organizationExecutor;
	private final OrganizationMetadataRebuild organizationMetadataRebuild;
	private final ExecutionPayloadCodec executionPayloadCodec;

	public OrganizationJobHandler(OrganizationExecutor organizationExecutor,
			OrganizationMetadataRebuild organizationMetadataRebuild, ExecutionPayloadCodec executionPayloadCodec) {
		this.organizationExecutor = organizationExecutor;
		this.organizationMetadataRebuild = organizationMetadataRebuild;
		this.executionPayloadCodec = executionPayloadCodec;
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.ORGANIZATION;
	}

	@Override
	public void handle(Execution execution, ClaimedExecution claimed, ExecutionOwnership ownership) {
		OrganizationExecuteRequest request = requestOf(execution, claimed);

		organizationMetadataRebuild.beforePlanning(request.toPreviewRequest());

		organizationExecutor.execute(request, execution, ownership);
	}

	/**
	 * Rebuilds the request from the row and its payload. A payload written in a
	 * shape this version does not know is refused rather than read as far as it
	 * goes: half-understood options would move files to somewhere nobody asked
	 * for.
	 */
	private OrganizationExecuteRequest requestOf(Execution execution, ClaimedExecution claimed) {
		OrganizationExecutePayload payload = executionPayloadCodec.decode(claimed.requestPayload(),
				OrganizationExecutePayload.class);

		if (payload.schemaVersion() == null
				|| payload.schemaVersion() != OrganizationConstants.EXECUTE_PAYLOAD_SCHEMA_VERSION) {
			throw new IllegalArgumentException("Organization payload schema " + payload.schemaVersion()
					+ " cannot be run by this version, which writes and reads schema "
					+ OrganizationConstants.EXECUTE_PAYLOAD_SCHEMA_VERSION);
		}

		return new OrganizationExecuteRequest(claimed.sourcePath(), claimed.targetPath(), execution.getRecursive(),
				payload.layout(), payload.limit(), payload.rebuildMetadata(), payload.rebuild(),
				payload.skipAlreadyOrganized(), payload.onlyCategories(), payload.onlySubcategories(),
				payload.onlyExtensions(), payload.onlyFileTypes(), payload.allowConflicts(),
				payload.overwriteExisting(), payload.locationSubdivision(), payload.locationMinConfidence(),
				payload.locationFallback(), false);
	}
}