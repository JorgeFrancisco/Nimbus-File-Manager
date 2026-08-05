package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCompletionWait;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineMessages;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineCleanupPayload;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineCleanupResult;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.settings.application.QuarantineFolderPolicy;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.NumberUtils;

/**
 * Asking for the records of absent quarantined files to be cleared.
 *
 * <p>
 * The reading happens first and decides whether there is anything to ask for.
 * Nothing absent means nothing queued: the operation that would have run,
 * cleared nothing and left a row saying so is exactly the kind of history that
 * makes a screen of real work harder to read.
 *
 * <p>
 * What the reading produces is a shortlist, not a verdict - the worker looks at
 * every file again under its lock. Waiting a second for it keeps the screen's
 * answer immediate in the ordinary case, where clearing a handful of rows takes
 * milliseconds.
 *
 * <p>
 * Each of the three ways this can end carries its own sentence, resolved here
 * rather than assembled by the screen: this runs inside the request, so it is
 * the last place that still knows the language the answer has to be in.
 */
@Service
public class QuarantineCleanupLauncher extends LocalizedComponent {

	private static final Duration RESPONSE_BUDGET = Duration.ofSeconds(1);

	private final QuarantineAbsenceScan quarantineAbsenceScan;
	private final QuarantineFolderPolicy quarantineFolderPolicy;
	private final ExecutionEnqueueService executionEnqueueService;
	private final ExecutionCompletionWait executionCompletionWait;
	private final ExecutionPayloadCodec executionPayloadCodec;
	private final ExecutionMessageCodec executionMessageCodec;
	private final ExecutionMapper executionMapper;

	public QuarantineCleanupLauncher(QuarantineAbsenceScan quarantineAbsenceScan,
			QuarantineFolderPolicy quarantineFolderPolicy, ExecutionEnqueueService executionEnqueueService,
			ExecutionCompletionWait executionCompletionWait, ExecutionPayloadCodec executionPayloadCodec,
			ExecutionMessageCodec executionMessageCodec, ExecutionMapper executionMapper) {
		this.quarantineAbsenceScan = quarantineAbsenceScan;
		this.quarantineFolderPolicy = quarantineFolderPolicy;
		this.executionEnqueueService = executionEnqueueService;
		this.executionCompletionWait = executionCompletionWait;
		this.executionPayloadCodec = executionPayloadCodec;
		this.executionMessageCodec = executionMessageCodec;
		this.executionMapper = executionMapper;
	}

	public QuarantineCleanupResult clearAbsent() {
		// Asked before the scan, not after it. Without a configured folder there is
		// no tree for the worker to hold and the request could not run - and the scan
		// itself would answer that every single record is absent, which is the one
		// answer this operation must never act on.
		if (quarantineFolderPolicy.root().isEmpty()) {
			return QuarantineCleanupResult.refused(message(QuarantineMessages.FOLDER_NOT_CONFIGURED));
		}

		List<UUID> absent = quarantineAbsenceScan.absent();

		if (absent.isEmpty()) {
			return QuarantineCleanupResult.nothingToClear(message("backend.quarantine.cleanupNothingAbsent"));
		}

		Execution queued = executionEnqueueService.enqueueOrExisting(request(absent));

		// What the row says, rather than a second sentence written here: the pass
		// counts records kept as well as removed, and a file that came back under the
		// lock is the outcome most worth reading.
		return executionCompletionWait.awaitTerminal(queued.getId(), RESPONSE_BUDGET)
				.map(finished -> QuarantineCleanupResult.cleared(NumberUtils.toInt(finished.getFilesMoved()),
						executionMapper.toResponse(finished).message()))
				.orElseGet(() -> QuarantineCleanupResult.queued(message("backend.quarantine.cleanupProcessing")));
	}

	/**
	 * The request, and the two things about it worth writing down.
	 *
	 * <p>
	 * <strong>No deduplication key:</strong> what the shortlist names is what was
	 * absent when it was read, and two readings taken a minute apart are two
	 * different sets. The queue would have to compare lists to tell them apart,
	 * which is what the second look under the lock already does per item, and
	 * better.
	 *
	 * <p>
	 * <strong>The quarantine folder is named</strong> because this clears records
	 * of files that should be in it, under the same port that deletes them - so it
	 * has to exclude, and be excluded by, everything else working on that tree. It
	 * is there by the time this runs: a request without one is refused before the
	 * scan.
	 */
	private Execution request(List<UUID> absent) {
		var started = QuarantineMessages.cleanupStarted(absent.size());

		String root = quarantineFolderPolicy.root().map(PathUtils::normalize).orElse(null);

		return Execution.builder().executionType(ExecutionType.QUARANTINE_CLEANUP).sourcePath(root).recursive(false)
				.executeFlag(true).filesFound(absent.size())
				.requestPayload(executionPayloadCodec.encode(
						new QuarantineCleanupPayload(QuarantineConstants.PAYLOAD_SCHEMA_VERSION, List.copyOf(absent))))
				.statusMessage(StatusMessage.coded(started.code(), executionMessageCodec.encode(started.args())))
				.build();
	}
}