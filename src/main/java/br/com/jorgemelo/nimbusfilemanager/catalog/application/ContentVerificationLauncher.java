package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentSuspect;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentVerificationPayload;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Asks, durably, for a file's content to be looked at.
 *
 * <p>
 * A row rather than a queue in memory, because the reason to ask is usually a
 * notification that has already been consumed: if the process dies between
 * noticing and reading, nothing would ever notice again. It survives restart
 * like every other execution, and the walk of the library is the second net
 * under it.
 *
 * <p>
 * The deduplication key is the file and nothing else. A save that produces forty
 * notifications should produce one reading, and it can, because what is being
 * asked is "what does this file contain now" rather than "confirm this
 * particular notification".
 */
@Service
public class ContentVerificationLauncher {

	private final ExecutionEnqueueService executionEnqueueService;
	private final ExecutionPayloadCodec executionPayloadCodec;

	public ContentVerificationLauncher(ExecutionEnqueueService executionEnqueueService,
			ExecutionPayloadCodec executionPayloadCodec) {
		this.executionEnqueueService = executionEnqueueService;
		this.executionPayloadCodec = executionPayloadCodec;
	}

	/**
	 * A reading already waiting answers this request; a reading already
	 * <em>running</em> does not.
	 *
	 * <p>
	 * The difference is the whole of the 1 + 1 rule, and it is not queue tidiness.
	 * A verification reads the file once: it stats, digests, stats again, and gives
	 * up the reading only if the file moved between those two stats. A save that
	 * lands after that - while the execution is still running - is not seen by it,
	 * and the state it commits is the older one. The successor is what carries
	 * "something happened after what the running one looked at", so refusing it
	 * loses an observation that nothing else will make again on its own.
	 *
	 * @param observedAt when the change that prompted this was observed, which the
	 * resulting fact is dated by. A burst that collapses into one execution keeps
	 * the first observation's instant: that is when the file started being what it
	 * now is
	 */
	public void verify(Long catalogFileId, String path, Instant observedAt, ExecutionTrigger trigger) {
		executionEnqueueService.enqueue(request(catalogFileId, path, observedAt, trigger));
	}

	/**
	 * Every suspicion one pass raised, admitted in a single go.
	 *
	 * <p>
	 * Not a loop of {@link #verify} for a reason that is not tidiness: admission
	 * takes a lock per identity and holds it to the caller's commit, and only a
	 * caller handing over the whole set lets those locks be taken in one order.
	 * Asked one at a time from inside a transaction, two passes sharing two files
	 * would take them in whatever order each happened to find them, and deadlock.
	 */
	public void verifyAll(List<ContentSuspect> suspects, Instant observedAt, ExecutionTrigger trigger) {
		if (suspects.isEmpty()) {
			return;
		}

		executionEnqueueService.enqueueAll(requestsFor(suspects, observedAt, trigger));
	}

	/**
	 * The same requests, built but not admitted - for the caller that has other
	 * intentions to admit in the same transaction and therefore has to hand them
	 * over together.
	 */
	public List<Execution> requestsFor(List<ContentSuspect> suspects, Instant observedAt, ExecutionTrigger trigger) {
		return suspects.stream()
				.map(suspect -> request(suspect.catalogFileId(), suspect.currentPath(), observedAt, trigger)).toList();
	}

	/** The one description of what a content verification is asked for with. */
	private Execution request(Long catalogFileId, String path, Instant observedAt, ExecutionTrigger trigger) {
		return Execution.builder().executionType(ExecutionType.CONTENT_VERIFICATION).triggerEvent(trigger)
				.sourcePath(path).executeFlag(true).dedupKey("content-verification:" + catalogFileId)
				.requestPayload(executionPayloadCodec
						.encode(new ContentVerificationPayload(ContentVerificationPayload.SCHEMA_VERSION,
								catalogFileId, observedAt)))
				.build();
	}
}