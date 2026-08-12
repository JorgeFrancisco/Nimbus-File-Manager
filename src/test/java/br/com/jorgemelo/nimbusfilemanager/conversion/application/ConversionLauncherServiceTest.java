package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.Progress;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.constants.ConversionConstants;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionExecutePayload;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionOptions;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionQuality;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ExecutionLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

@ExtendWith(MockitoExtension.class)
class ConversionLauncherServiceTest {

	@TempDir
	private Path tempDir;

	@Mock
	private CatalogFileRepository catalogFileRepository;

	@Mock
	private ExecutionEnqueueService executionEnqueueService;

	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(new ObjectMapper());

	/**
	 * The tree goes in the columns because it is what the worker locks; the videos
	 * go in the payload because a batch is a set somebody picked one by one, and no
	 * folder describes it.
	 */
	@Test
	void queuesTheTreeAsColumnsAndTheVideosAsPayload() {
		Path library = tempDir.resolve("library");

		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		when(catalogFileRepository.findByCatalogFilePublicIdIn(new UUID[] { first, second }))
				.thenReturn(List.of(CatalogFiles.at(library.resolve("clip.mp4"))));
		when(executionEnqueueService.enqueue(any())).thenAnswer(invocation -> {
			Execution queued = invocation.getArgument(0);

			queued.setStatus(ExecutionStatus.PENDING);
			queued.setExecutionPublicId(UUID.randomUUID());

			return Optional.of(queued);
		});

		launcher().launch(List.of(first, second), ConversionOptions.defaults());

		ArgumentCaptor<Execution> captor = ArgumentCaptor.forClass(Execution.class);

		verify(executionEnqueueService).enqueue(captor.capture());

		Execution queued = captor.getValue();

		Assertions.assertThat(queued.getExecutionType()).isEqualTo(ExecutionType.CONVERSION);
		Assertions.assertThat(queued.getSourcePath()).isEqualTo(PathUtils.normalize(library));
		Assertions.assertThat(queued.getFilesFound()).isEqualTo(2);
		Assertions.assertThat(queued.getDedupKey()).isNull();

		ConversionExecutePayload payload = executionPayloadCodec.decode(queued.getRequestPayload(),
				ConversionExecutePayload.class);

		Assertions.assertThat(payload.schemaVersion()).isEqualTo(ConversionConstants.EXECUTE_PAYLOAD_SCHEMA_VERSION);
		Assertions.assertThat(payload.publicIds()).containsExactly(first, second);
		Assertions.assertThat(payload.quality()).isEqualTo(ConversionQuality.BALANCED);
	}

	@Test
	void refusesARequestThatNamesNoVideos() {
		ConversionLauncherService launcher = launcher();

		ConversionOptions options = ConversionOptions.defaults();

		Assertions.assertThatThrownBy(() -> launcher.launch(List.of(), options))
				.isInstanceOf(IllegalArgumentException.class);

		verify(executionEnqueueService, never()).enqueue(any());
	}

	/**
	 * These carry no deduplication key, so a refusal is the queue answering
	 * something nobody has described - and a request that silently vanishes is
	 * worse than one that raises where somebody can be told.
	 */
	@Test
	void raisesWhenTheQueueRefusesARequestThatCannotBeADuplicate() {
		UUID video = UUID.randomUUID();

		when(catalogFileRepository.findByCatalogFilePublicIdIn(new UUID[] { video }))
				.thenReturn(List.of(CatalogFiles.at(tempDir.resolve("library").resolve("clip.mp4"))));
		when(executionEnqueueService.enqueue(any())).thenReturn(Optional.empty());

		ConversionLauncherService launcher = launcher();

		List<UUID> ids = List.of(video);

		ConversionOptions options = ConversionOptions.defaults();

		Assertions.assertThatThrownBy(() -> launcher.launch(ids, options)).isInstanceOf(IllegalStateException.class);
	}

	private ConversionLauncherService launcher() {
		return new ConversionLauncherService(catalogFileRepository, executionEnqueueService, executionPayloadCodec,
				new ExecutionMapper(new ExecutionMessageCodec(new ObjectMapper()), mock(ExecutionLabels.class),
						Progress.reader(), Progress.estimator()),
				new ExecutionMessageCodec(new ObjectMapper()));
	}
}