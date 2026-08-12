package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.Progress;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationFallbackMode;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationSubdivision;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MetadataRebuildField;
import br.com.jorgemelo.nimbusfilemanager.organization.application.constants.OrganizationConstants;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecutePayload;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ExecutionLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

@ExtendWith(MockitoExtension.class)
class OrganizationLauncherServiceTest {

	@TempDir
	private Path tempDir;

	@Mock
	private ExecutionEnqueueService executionEnqueueService;

	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(new ObjectMapper());

	/**
	 * The folders are columns and everything else is payload. Both halves matter:
	 * the worker locks what the columns say, and it can only rebuild the request if
	 * the payload carried the rest.
	 */
	@Test
	void queuesTheFoldersAsColumnsAndEverythingElseAsPayload() {
		Path source = tempDir.resolve("input");
		Path target = tempDir.resolve("target");

		OrganizationExecuteRequest request = new OrganizationExecuteRequest(source.toString(), target.toString(), false,
				OrganizationLayout.YEAR_MONTH_DAY, 250, true, List.of(MetadataRebuildField.GPS), false, null, null,
				null, null, true, true, LocationSubdivision.COUNTRY_STATE_CITY, null, LocationFallbackMode.IGNORE);

		when(executionEnqueueService.enqueue(any())).thenAnswer(OrganizationLauncherServiceTest::asTheQueueWouldAnswer);

		launcher().launch(request);

		Execution queued = queuedExecution();

		Assertions.assertThat(queued.getExecutionType()).isEqualTo(ExecutionType.ORGANIZATION);
		Assertions.assertThat(queued.getSourcePath()).isEqualTo(PathUtils.normalize(source));
		Assertions.assertThat(queued.getTargetPath()).isEqualTo(PathUtils.normalize(target));
		Assertions.assertThat(queued.getRecursive()).isFalse();
		Assertions.assertThat(queued.getExecuteFlag()).isTrue();

		OrganizationExecutePayload payload = executionPayloadCodec.decode(queued.getRequestPayload(),
				OrganizationExecutePayload.class);

		Assertions.assertThat(payload.schemaVersion()).isEqualTo(OrganizationConstants.EXECUTE_PAYLOAD_SCHEMA_VERSION);
		Assertions.assertThat(payload.layout()).isEqualTo(OrganizationLayout.YEAR_MONTH_DAY);
		Assertions.assertThat(payload.limit()).isEqualTo(250);
		Assertions.assertThat(payload.allowConflicts()).isTrue();
		Assertions.assertThat(payload.overwriteExisting()).isTrue();
		Assertions.assertThat(payload.rebuildMetadata()).isTrue();
		Assertions.assertThat(payload.rebuild()).containsExactly(MetadataRebuildField.GPS);
		Assertions.assertThat(payload.locationSubdivision()).isEqualTo(LocationSubdivision.COUNTRY_STATE_CITY);
	}

	/**
	 * The folders live in the columns and nowhere else. A second copy in the
	 * payload could disagree with them, and the worker takes its locks from the
	 * columns - so it would be holding one tree while working on another.
	 */
	@Test
	void keepsTheFoldersOutOfThePayload() {
		when(executionEnqueueService.enqueue(any())).thenAnswer(OrganizationLauncherServiceTest::asTheQueueWouldAnswer);

		launcher().launch(request());

		Assertions.assertThat(queuedExecution().getRequestPayload()).doesNotContain("Path").doesNotContain("input")
				.doesNotContain("target");
	}

	/**
	 * Two requests over the same folders are two things a person asked for, and the
	 * second may carry different options entirely. Nothing about them is a
	 * duplicate, so nothing keys them.
	 */
	@Test
	void queuesWithoutADeduplicationKeySoASecondRequestIsNeverSwallowed() {
		when(executionEnqueueService.enqueue(any())).thenAnswer(OrganizationLauncherServiceTest::asTheQueueWouldAnswer);

		launcher().launch(request());

		Assertions.assertThat(queuedExecution().getDedupKey()).isNull();
	}

	/**
	 * The row carries no key, so the database has nothing to refuse it by. If it
	 * refuses anyway, something has changed that nobody has described, and saying
	 * so beats returning an execution that does not exist.
	 */
	@Test
	void raisesWhenTheQueueRefusesARequestThatCannotBeADuplicate() {
		when(executionEnqueueService.enqueue(any())).thenReturn(Optional.empty());

		OrganizationLauncherService launcher = launcher();
		OrganizationExecuteRequest request = request();

		Assertions.assertThatThrownBy(() -> launcher.launch(request)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("no deduplication key");
	}

	/**
	 * What the queue does to a row on the way in, so the response built from it is
	 * the one a caller really gets: PENDING, with an identity to poll by.
	 */
	private static Optional<Execution> asTheQueueWouldAnswer(InvocationOnMock invocation) {
		Execution queued = invocation.getArgument(0);

		queued.setStatus(ExecutionStatus.PENDING);
		queued.setExecutionPublicId(UUID.randomUUID());

		return Optional.of(queued);
	}

	private Execution queuedExecution() {
		ArgumentCaptor<Execution> captor = ArgumentCaptor.forClass(Execution.class);

		verify(executionEnqueueService).enqueue(captor.capture());

		return captor.getValue();
	}

	private OrganizationLauncherService launcher() {
		return new OrganizationLauncherService(executionEnqueueService, executionPayloadCodec,
				new ExecutionMapper(new ExecutionMessageCodec(new ObjectMapper()), new ExecutionLabels(),
						Progress.reader(), Progress.estimator()));
	}

	private OrganizationExecuteRequest request() {
		return new OrganizationExecuteRequest(tempDir.resolve("input").toString(),
				tempDir.resolve("target").toString(), true, OrganizationLayout.DEFAULT, 50, false, null, null, null,
				null, null, null, false, false);
	}
}