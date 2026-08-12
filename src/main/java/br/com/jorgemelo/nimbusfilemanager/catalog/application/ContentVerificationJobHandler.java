package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentObservation;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentState;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentVerificationPayload;
import br.com.jorgemelo.nimbusfilemanager.catalog.domain.enums.ContentOutcome;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads a file and settles what the catalog says it contains.
 *
 * <p>
 * This is where the digest is paid for, and the reason it is here rather than
 * where the suspicion arose: the watcher notices changes on a thread that polls
 * every half second and also has to notice a reconfiguration, and reading a
 * gigabyte there would stall every other observation behind it.
 *
 * <p>
 * <b>What it verifies is now, not then.</b> The execution names a file and
 * carries no snapshot of the notification that asked for it, so forty
 * notifications from one save collapse into one reading whose answer is still
 * the current one. The same property is what closes the race in the other
 * direction: a file written again while this was reading it leaves the state on
 * disk different from the state that was hashed, and rather than commit an
 * answer about a moment that has passed, it looks again.
 */
@Slf4j
@Component
public class ContentVerificationJobHandler implements ExecutionJobHandler {

	/**
	 * How many times a file that keeps changing under the reading is re-read
	 * before the job settles for what it has. A file being written continuously
	 * would otherwise hold a worker forever; whatever is left unsettled raises the
	 * same suspicion again on the next observation or the next walk.
	 */
	private static final int MAX_ATTEMPTS = 3;

	private final CatalogFileRepository catalogFileRepository;
	private final ContentReconciliation contentReconciliation;
	private final FileHashService fileHashService;
	private final ExecutionProgressService executionProgressService;
	private final ExecutionPayloadCodec executionPayloadCodec;
	private final Clock clock;

	public ContentVerificationJobHandler(CatalogFileRepository catalogFileRepository,
			ContentReconciliation contentReconciliation, FileHashService fileHashService,
			ExecutionProgressService executionProgressService, ExecutionPayloadCodec executionPayloadCodec,
			Clock clock) {
		this.catalogFileRepository = catalogFileRepository;
		this.contentReconciliation = contentReconciliation;
		this.fileHashService = fileHashService;
		this.executionProgressService = executionProgressService;
		this.executionPayloadCodec = executionPayloadCodec;
		this.clock = clock;
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.CONTENT_VERIFICATION;
	}

	/**
	 * Reading one file is not work worth resuming halfway: starting over costs the
	 * same and is always correct.
	 */
	@Override
	public boolean resumable() {
		return false;
	}

	@Override
	public void handle(Execution execution, ClaimedExecution claimed, ExecutionOwnership ownership) {
		ContentVerificationPayload payload = executionPayloadCodec.decode(claimed.requestPayload(),
				ContentVerificationPayload.class);

		executionProgressService.updateTotal(ownership, 1);

		ExecutionStatus status = verify(payload.catalogFileId(), causeOf(payload));

		executionProgressService.finishCommand(ownership, status, new ExecutionCounts(1, 0, 0, 0),
				new ExecutionMessage(ExecutionMessages.VERIFYING_CONTENT, List.of()));
	}

	/**
	 * When the change happened, which is not when this finished reading it. A
	 * journal replay can be days old and the reading minutes long; dating the fact
	 * by the end of the work would put both errors into the history at once.
	 */
	private Instant causeOf(ContentVerificationPayload payload) {
		return payload.observedAt() == null ? Instant.now(clock) : payload.observedAt();
	}

	private ExecutionStatus verify(Long catalogFileId, Instant occurredAt) {
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			CatalogFile file = catalogFileRepository.findById(catalogFileId).orElse(null);

			if (file == null || file.getLocation() == null) {
				// Purged while this waited to run. There is nothing to be right about.
				return ExecutionStatus.FINISHED;
			}

			Path path = PathUtils.normalizePath(file.getLocation().getCurrentPath());

			BasicFileAttributes before = statOf(path);

			if (before == null) {
				// Gone from disk. Whether the catalog should forget it is the
				// reconciliation of presence, not of content, and that is the walk's
				// question rather than this one's.
				return ExecutionStatus.FINISHED;
			}

			ContentState observed = new ContentState(fileHashService.sha256(path), before.size(),
					CatalogTimestamp.observed(before.lastModifiedTime()), null);

			BasicFileAttributes after = statOf(path);

			if (after == null || moved(before, after)) {
				log.debug("{} changed while it was being read; looking again (attempt {})", path, attempt);

				continue;
			}

			return applied(file, observed, path, occurredAt);
		}

		log.info("Catalog file {} kept changing while it was read; leaving it to the next observation",
				catalogFileId);

		return ExecutionStatus.FINISHED;
	}

	private ExecutionStatus applied(CatalogFile file, ContentState observed, Path path, Instant occurredAt) {
		ContentOutcome outcome = contentReconciliation.reconcile(file,
				new ContentObservation(observed, CatalogEventSources.WATCHER, occurredAt));

		log.debug("Content verification of {} ended as {}", path, outcome);

		return outcome == ContentOutcome.CONFLICT ? ExecutionStatus.FINISHED_WITH_ERRORS : ExecutionStatus.FINISHED;
	}

	/**
	 * Whether the file moved under the reading. Size and modification time are
	 * enough for this question and a second digest would not be: what is being
	 * asked is not whether the bytes differ but whether the file was touched
	 * again, and being wrong costs one extra reading rather than a wrong answer.
	 */
	private static boolean moved(BasicFileAttributes before, BasicFileAttributes after) {
		return before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime());
	}

	private static BasicFileAttributes statOf(Path path) {
		try {
			return Files.readAttributes(path, BasicFileAttributes.class);
		} catch (IOException _) {
			return null;
		}
	}
}