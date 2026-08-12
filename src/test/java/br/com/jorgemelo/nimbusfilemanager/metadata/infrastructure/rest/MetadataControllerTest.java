package br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.MetadataRebuildLauncher;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * The scripted way in.
 *
 * <p>
 * It used to run the whole pass inside the request and answer what it had done.
 * The answer now is the row to follow, which is what a caller can still act on
 * after the connection is gone - and, unlike the old one, is the same answer the
 * settings screen is looking at.
 */
class MetadataControllerTest {

	private static final UUID PUBLIC_ID = UUID.fromString("0199a6f0-0000-7000-8000-000000000001");

	private final MetadataRebuildLauncher metadataRebuildLauncher = mock(MetadataRebuildLauncher.class);

	private final MetadataController controller = new MetadataController(metadataRebuildLauncher);

	@Test
	void aQueuedRebuildAnswersWithTheExecutionToFollow() {
		when(metadataRebuildLauncher.launch(any(), any(), anyBoolean(), any()))
				.thenReturn(Optional.of(Execution.builder().id(1L).executionPublicId(PUBLIC_ID).build()));

		ResponseEntity<Void> response = controller.rebuild(request());

		Assertions.assertThat(response.getStatusCode().value()).isEqualTo(202);
		Assertions.assertThat(response.getHeaders().getFirst("Location"))
				.isEqualTo("/api/executions/" + PUBLIC_ID);
	}

	/**
	 * Nothing was queued because the application is shutting down. Answering 202
	 * would point at a row that does not exist, so the caller is told the service
	 * is not taking work rather than given a link to nothing.
	 */
	@Test
	void aRequestArrivingWhileTheApplicationIsClosingIsRefused() {
		when(metadataRebuildLauncher.launch(any(), any(), anyBoolean(), any())).thenReturn(Optional.empty());

		Assertions.assertThat(controller.rebuild(request()).getStatusCode().value()).isEqualTo(503);
	}

	private MetadataRebuildRequest request() {
		return new MetadataRebuildRequest("C:/input", null, null, null, 100, false, null);
	}
}