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
 * Builds a preview that came off the queue.
 *
 * <p>
 * A type of its own rather than a flag on the organization type, because the two
 * answer differently to every question the queue asks. This one is
 * <strong>resumable</strong>: it writes only rows nobody can see until they are
 * published, so a second attempt from the start cannot be told apart from a
 * first - where an organization is explicitly not, because half its files have
 * already moved by the time anyone notices it stopped. It also needs no
 * ownership of the trees it reads, and it should not be competing for the single
 * slot a real organization occupies.
 *
 * <p>
 * Nothing here writes to the library - what it produces is a description of what
 * a run would do - but the ownership travels with it all the same, because the
 * execution row is written: the total, the phase and the outcome are writes
 * about a taking and answer to it.
 */
@Component
public class OrganizationPreviewJobHandler implements ExecutionJobHandler {

	private final OrganizationPreviewJob organizationPreviewJob;
	private final OrganizationMetadataRebuild organizationMetadataRebuild;
	private final ExecutionPayloadCodec executionPayloadCodec;

	public OrganizationPreviewJobHandler(OrganizationPreviewJob organizationPreviewJob,
			OrganizationMetadataRebuild organizationMetadataRebuild, ExecutionPayloadCodec executionPayloadCodec) {
		this.organizationPreviewJob = organizationPreviewJob;
		this.organizationMetadataRebuild = organizationMetadataRebuild;
		this.executionPayloadCodec = executionPayloadCodec;
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.ORGANIZATION_PREVIEW;
	}

	@Override
	public boolean resumable() {
		return true;
	}

	@Override
	public void handle(Execution execution, ClaimedExecution claimed, ExecutionOwnership ownership) {
		OrganizationExecuteRequest request = requestOf(execution, claimed);

		organizationMetadataRebuild.beforePlanning(request.toPreviewRequest());

		organizationPreviewJob.run(request, execution, ownership);
	}

	/**
	 * Rebuilds the request from the row and its payload. A payload written in a
	 * shape this version does not know is refused rather than read as far as it
	 * goes: a half-understood option would describe a plan nobody asked for, and
	 * the user would decide on it.
	 */
	private OrganizationExecuteRequest requestOf(Execution execution, ClaimedExecution claimed) {
		OrganizationExecutePayload payload = executionPayloadCodec.decode(claimed.requestPayload(),
				OrganizationExecutePayload.class);

		if (payload.schemaVersion() == null
				|| payload.schemaVersion() != OrganizationConstants.EXECUTE_PAYLOAD_SCHEMA_VERSION) {
			throw new IllegalArgumentException("Organization preview payload schema " + payload.schemaVersion()
					+ " cannot be run by this version, which writes and reads schema "
					+ OrganizationConstants.EXECUTE_PAYLOAD_SCHEMA_VERSION);
		}

		return new OrganizationExecuteRequest(claimed.sourcePath(), claimed.targetPath(), execution.getRecursive(),
				payload.layout(), payload.limit(), payload.rebuildMetadata(), payload.rebuild(),
				payload.skipAlreadyOrganized(), payload.onlyCategories(), payload.onlySubcategories(),
				payload.onlyExtensions(), payload.onlyFileTypes(), payload.allowConflicts(),
				payload.overwriteExisting(), payload.locationSubdivision(), payload.locationMinConfidence(),
				payload.locationFallback(), true);
	}
}