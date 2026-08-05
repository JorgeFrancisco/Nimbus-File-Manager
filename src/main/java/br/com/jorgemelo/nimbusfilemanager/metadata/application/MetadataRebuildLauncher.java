package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataMessages;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildPayload;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MetadataRebuildField;
import br.com.jorgemelo.nimbusfilemanager.shared.application.BackgroundWorkGate;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Asking for a metadata rebuild.
 *
 * <p>
 * The application decides nothing about the work beyond what was asked for: it
 * validates the folder, writes the request and hands back the row to follow.
 * Reading the files, extracting their metadata and writing the catalog all
 * happen in the worker, which is where the exiftool processes belong.
 */
@Service
public class MetadataRebuildLauncher {

	private final ExecutionEnqueueService executionEnqueueService;
	private final ExecutionPayloadCodec executionPayloadCodec;
	private final BackgroundWorkGate backgroundWorkGate;

	public MetadataRebuildLauncher(ExecutionEnqueueService executionEnqueueService,
			ExecutionPayloadCodec executionPayloadCodec, BackgroundWorkGate backgroundWorkGate) {
		this.executionEnqueueService = executionEnqueueService;
		this.executionPayloadCodec = executionPayloadCodec;
		this.backgroundWorkGate = backgroundWorkGate;
	}

	/**
	 * Queues the rebuild, or joins the one already waiting for the same thing.
	 *
	 * @return empty when the application is shutting down. A row queued then would
	 * be claimed by nobody before the pool goes, and nothing is lost by not writing
	 * it: the folder is still there to be asked about
	 */
	public Optional<Execution> launch(String sourcePath, List<MetadataRebuildField> refresh, boolean dryRun,
			LocalDateTime notAnalysedSince) {
		if (backgroundWorkGate.standDown()) {
			return Optional.empty();
		}

		String normalized = PathUtils.normalize(PathUtils.normalizePath(sourcePath));

		Execution queued = Execution.builder().executionType(ExecutionType.METADATA_REBUILD).sourcePath(normalized)
				.recursive(true).executeFlag(!dryRun).dedupKey(dedupKey(normalized, dryRun))
				.requestPayload(executionPayloadCodec.encode(new MetadataRebuildPayload(
						MetadataMessages.PAYLOAD_SCHEMA_VERSION, normalized, refresh, dryRun, notAnalysedSince)))
				.statusMessage(StatusMessage.code(MetadataMessages.STARTED)).build();

		return Optional.of(executionEnqueueService.enqueueOrExisting(queued));
	}

	/**
	 * A folder and a mode, and nothing else.
	 *
	 * <p>
	 * The fields are deliberately out of the key: asking for dates and then for
	 * dates and cameras over the same folder while the first is still waiting is
	 * one intention expressed twice, and the second ask would only re-read files
	 * the first is about to read anyway. A simulation, on the other hand, is a
	 * different question about the same folder - it writes nothing and answers
	 * something else - so it never collapses onto a real pass.
	 */
	private String dedupKey(String sourcePath, boolean dryRun) {
		return (dryRun ? "simulate:" : "rebuild:") + sourcePath;
	}
}