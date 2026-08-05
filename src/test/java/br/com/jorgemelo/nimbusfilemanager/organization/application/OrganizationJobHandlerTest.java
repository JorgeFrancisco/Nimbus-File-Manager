package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationFallbackMode;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationSubdivision;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.MetadataRebuildService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildRequest;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MetadataRebuildField;
import br.com.jorgemelo.nimbusfilemanager.organization.application.constants.OrganizationConstants;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecutePayload;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

@ExtendWith(MockitoExtension.class)
class OrganizationJobHandlerTest {

	@TempDir
	private Path tempDir;

	@Mock
	private OrganizationExecutor organizationExecutor;

	@Mock
	private MetadataRebuildService metadataRebuildService;

	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(new ObjectMapper());

	@Test
	void answersForTheOrganizationType() {
		Assertions.assertThat(handler().type()).isEqualTo(ExecutionType.ORGANIZATION);
	}

	/**
	 * Never resumable, and the default is what says so: a run that stopped halfway
	 * already moved files, and starting again would begin from a library the first
	 * pass had rearranged.
	 */
	@Test
	void refusesToBeRerunFromTheStartAfterBeingAbandoned() {
		Assertions.assertThat(handler().resumable()).isFalse();
	}

	/**
	 * Only one at a time, which is the guarantee the old runner's flag gave and the
	 * limit that replaces it has to keep.
	 */
	@Test
	void allowsOnlyOneOrganizationAtATime() {
		Assertions.assertThat(handler().concurrencyLimit()).isEqualTo(1);
	}

	/**
	 * The folders come from the row and everything else from the payload, and the
	 * run is never a dry run - a claimed organization is the real move.
	 */
	@Test
	void rebuildsTheRequestFromTheRowAndItsPayload() {
		Path source = tempDir.resolve("input");
		Path target = tempDir.resolve("target");

		Execution execution = Execution.builder().id(3L).recursive(false).build();

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		handler().handle(execution, claimed(source, target, payload(OrganizationLayout.YEAR_MONTH_DAY, 250)),
				ownership);

		ArgumentCaptor<OrganizationExecuteRequest> captor = ArgumentCaptor.forClass(OrganizationExecuteRequest.class);

		verify(organizationExecutor).execute(captor.capture(), eq(execution), eq(ownership));

		OrganizationExecuteRequest request = captor.getValue();

		Assertions.assertThat(request.source()).isEqualTo(source);
		Assertions.assertThat(request.target()).isEqualTo(target);
		Assertions.assertThat(request.recursiveValue()).isFalse();
		Assertions.assertThat(request.layoutValue()).isEqualTo(OrganizationLayout.YEAR_MONTH_DAY);
		Assertions.assertThat(request.safeLimit()).isEqualTo(250);
		Assertions.assertThat(request.dryRunValue()).isFalse();
	}

	/**
	 * The refresh happens where the work happens. Planning from stale dates would
	 * file photographs under the wrong month, and the application no longer has a
	 * thread of its own in which to do it first.
	 */
	@Test
	void refreshesTheMetadataThePlanWillBeBuiltFromBeforeOrganizing() {
		Execution execution = Execution.builder().id(4L).recursive(true).build();

		OrganizationExecutePayload payload = new OrganizationExecutePayload(
				OrganizationConstants.EXECUTE_PAYLOAD_SCHEMA_VERSION, OrganizationLayout.DEFAULT, 10, true,
				List.of(MetadataRebuildField.GPS), null, null, null, null, null, null, null, LocationSubdivision.NONE,
				null, LocationFallbackMode.IGNORE);

		handler().handle(execution, claimed(tempDir.resolve("input"), tempDir.resolve("target"), payload),
				mock(ExecutionOwnership.class));

		ArgumentCaptor<MetadataRebuildRequest> captor = ArgumentCaptor.forClass(MetadataRebuildRequest.class);

		verify(metadataRebuildService).rebuild(captor.capture());

		Assertions.assertThat(captor.getValue().refresh()).containsExactly(MetadataRebuildField.GPS);
	}

	@Test
	void leavesTheMetadataAloneWhenTheRequestDidNotAskForIt() {
		Execution execution = Execution.builder().id(5L).recursive(true).build();

		handler().handle(execution,
				claimed(tempDir.resolve("input"), tempDir.resolve("target"), payload(OrganizationLayout.DEFAULT, 10)),
				mock(ExecutionOwnership.class));

		verify(metadataRebuildService, never()).rebuild(any());
	}

	/**
	 * A payload written in a shape this version does not know is refused outright.
	 * Reading it as far as it goes would mean moving files with half the options
	 * somebody chose.
	 */
	@Test
	void refusesAPayloadWrittenInAShapeItDoesNotKnow() {
		Execution execution = Execution.builder().id(6L).recursive(true).build();

		OrganizationExecutePayload fromTheFuture = new OrganizationExecutePayload(
				OrganizationConstants.EXECUTE_PAYLOAD_SCHEMA_VERSION + 1, OrganizationLayout.DEFAULT, 10, null, null,
				null, null, null, null, null, null, null, null, null, null);

		ClaimedExecution claimed = claimed(tempDir.resolve("input"), tempDir.resolve("target"), fromTheFuture);

		OrganizationJobHandler handler = handler();

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		Assertions.assertThatThrownBy(() -> handler.handle(execution, claimed, ownership))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be run by this version");

		verify(organizationExecutor, never()).execute(any(), any(), any());
	}

	private OrganizationJobHandler handler() {
		return new OrganizationJobHandler(organizationExecutor, new OrganizationMetadataRebuild(metadataRebuildService),
				executionPayloadCodec);
	}

	private ClaimedExecution claimed(Path source, Path target, OrganizationExecutePayload payload) {
		return new ClaimedExecution(1L, ExecutionType.ORGANIZATION.name(), source.toString(), target.toString(),
				executionPayloadCodec.encode(payload));
	}

	private OrganizationExecutePayload payload(OrganizationLayout layout, Integer limit) {
		return new OrganizationExecutePayload(OrganizationConstants.EXECUTE_PAYLOAD_SCHEMA_VERSION, layout, limit, null,
				null, null, null, null, null, null, null, null, LocationSubdivision.NONE, null,
				LocationFallbackMode.IGNORE);
	}
}