package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentVerificationPayload;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Asking for a file to be read, from a thread that must not read it.
 *
 * <p>
 * The watcher polls every half second and a digest of a large file takes far
 * longer than that, so the reading is queued as a durable execution instead.
 * What matters here is what the request carries: the file to read, the moment
 * the change was observed - which is what the resulting fact will be dated by -
 * and a key that collapses a burst of notifications about one file into a
 * single reading rather than one per notification.
 */
class ContentVerificationLauncherTest {

	private static final Instant OBSERVED_AT = Instant.parse("2026-08-14T06:00:00Z");

	private final ExecutionEnqueueService executionEnqueueService = mock(ExecutionEnqueueService.class);
	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(
			new ObjectMapper().findAndRegisterModules());

	private final ContentVerificationLauncher launcher = new ContentVerificationLauncher(executionEnqueueService,
			executionPayloadCodec);

	@Test
	void queuesTheReadingWithTheFileAndTheMomentTheChangeWasSeen() {
		launcher.verify(7L, "D:\\library\\photo.jpg", OBSERVED_AT, ExecutionTrigger.FILE_EVENT);

		ArgumentCaptor<Execution> queued = ArgumentCaptor.forClass(Execution.class);

		verify(executionEnqueueService).enqueue(queued.capture());

		Assertions.assertThat(queued.getValue().getExecutionType()).isEqualTo(ExecutionType.CONTENT_VERIFICATION);
		Assertions.assertThat(queued.getValue().getSourcePath()).isEqualTo("D:\\library\\photo.jpg");
		Assertions.assertThat(queued.getValue().getTriggerEvent()).isEqualTo(ExecutionTrigger.FILE_EVENT);

		ContentVerificationPayload payload = executionPayloadCodec.decode(queued.getValue().getRequestPayload(),
				ContentVerificationPayload.class);

		Assertions.assertThat(payload.catalogFileId()).isEqualTo(7L);
		Assertions.assertThat(payload.observedAt()).isEqualTo(OBSERVED_AT);
		Assertions.assertThat(payload.schemaVersion()).isEqualTo(ContentVerificationPayload.SCHEMA_VERSION);
	}

	/**
	 * An application saving a file produces several notifications about it, and
	 * reading it once per notification would read a large file several times over
	 * for one edit. The key is the file, so the queue collapses them.
	 */
	@Test
	void everyRequestAboutOneFileCarriesTheSameKey() {
		launcher.verify(7L, "D:\\library\\photo.jpg", OBSERVED_AT, ExecutionTrigger.FILE_EVENT);
		launcher.verify(7L, "D:\\library\\photo.jpg", OBSERVED_AT.plusSeconds(1), ExecutionTrigger.FILE_EVENT);
		launcher.verify(8L, "D:\\library\\other.jpg", OBSERVED_AT, ExecutionTrigger.FILE_EVENT);

		ArgumentCaptor<Execution> queued = ArgumentCaptor.forClass(Execution.class);

		verify(executionEnqueueService, times(3)).enqueue(queued.capture());

		Assertions.assertThat(queued.getAllValues()).extracting(Execution::getDedupKey)
				.containsExactly("content-verification:7", "content-verification:7", "content-verification:8");
	}
}