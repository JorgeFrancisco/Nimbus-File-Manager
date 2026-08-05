package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.constants.ConversionConstants;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.constants.ConversionMessages;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionExecutePayload;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionOptions;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Asking for a batch of videos to be converted.
 *
 * <p>
 * Nothing is encoded here. The row goes in PENDING and a worker claims it,
 * which is what took hours of ffmpeg out of the process serving the screen -
 * and what lets a batch of two hundred videos carry on when that process is
 * closed.
 *
 * <p>
 * The folder goes in the columns because it is what the worker locks; the
 * videos go in the payload because a batch is a set somebody picked one by one,
 * and no folder describes it.
 *
 * <p>
 * Never deduplicated: two batches over the same folder are two things a person
 * asked for, and the second may carry a different quality profile entirely.
 * What stops them running at once is the per-type limit in the worker.
 */
@Service
public class ConversionLauncherService extends LocalizedComponent {

	private final CatalogFileRepository catalogFileRepository;
	private final ExecutionEnqueueService executionEnqueueService;
	private final ExecutionPayloadCodec executionPayloadCodec;
	private final ExecutionMapper executionMapper;
	private final ExecutionMessageCodec executionMessageCodec;

	public ConversionLauncherService(CatalogFileRepository catalogFileRepository,
			ExecutionEnqueueService executionEnqueueService, ExecutionPayloadCodec executionPayloadCodec,
			ExecutionMapper executionMapper, ExecutionMessageCodec executionMessageCodec) {
		this.catalogFileRepository = catalogFileRepository;
		this.executionEnqueueService = executionEnqueueService;
		this.executionPayloadCodec = executionPayloadCodec;
		this.executionMapper = executionMapper;
		this.executionMessageCodec = executionMessageCodec;
	}

	private StatusMessage coded(ExecutionMessage message) {
		return StatusMessage.coded(message.code(), executionMessageCodec.encode(message.args()));
	}

	public ExecutionResponse launch(List<UUID> publicIds, ConversionOptions options) {
		if (publicIds == null || publicIds.isEmpty()) {
			throw new IllegalArgumentException(message("backend.conversion.noneSelected"));
		}

		ConversionOptions effective = options == null ? ConversionOptions.defaults() : options;

		String folder = folderOf(publicIds);

		Execution queued = Execution.builder().executionType(ExecutionType.CONVERSION).sourcePath(folder)
				.targetPath(folder).recursive(false).executeFlag(true).filesFound(publicIds.size())
				.requestPayload(executionPayloadCodec.encode(payloadOf(publicIds, effective)))
				.statusMessage(coded(ConversionMessages.started(publicIds.size()))).build();

		return executionMapper.toResponse(executionEnqueueService.enqueue(queued).orElseThrow(
				() -> new IllegalStateException("A conversion was refused as a duplicate, and conversions carry no "
						+ "deduplication key - so the row was refused for a reason nobody has described yet")));
	}

	/**
	 * The tree the batch lives in, which is what the worker takes its locks over.
	 * Read from the first file that still exists, the same answer the conversion
	 * itself used to work out once it had loaded them.
	 */
	private String folderOf(List<UUID> publicIds) {
		return catalogFileRepository.findByPublicIdIn(publicIds).stream().findFirst().map(CatalogFile::getFileKey)
				.map(PathUtils::normalizePath).map(Path::getParent).map(Path::toString).orElse(null);
	}

	private ConversionExecutePayload payloadOf(List<UUID> publicIds, ConversionOptions options) {
		return new ConversionExecutePayload(ConversionConstants.EXECUTE_PAYLOAD_SCHEMA_VERSION, List.copyOf(publicIds),
				options.quality(), options.audio(), options.disposition(), options.nameAffix(),
				options.affixPosition());
	}
}