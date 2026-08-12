package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.ContentVerificationLauncher;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentSuspect;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.EligibilityAnnouncer;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationPathKey;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MissingFile;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.Scan;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.CatalogFactProvenance;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.CatalogLifecycleWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * Brings the catalog into agreement with what a walk of the disk found.
 *
 * <p>
 * The write half of reconciliation, kept apart from the long walk so the
 * comparison runs outside any transaction while only these mutations execute
 * inside one. A separate bean rather than a method, for a reason that is not
 * style: Spring's {@code @Transactional} does not apply to self-invocation, so
 * the pass has to call the write step across a bean boundary for a transaction
 * to open at all.
 *
 * <p>
 * <b>It acts on everything it found.</b> Its predecessor read the samples - the
 * hundred rows a screen shows - so a pass could report five thousand files gone
 * and mark a hundred of them, stating the difference truthfully in one number
 * and repairing a fiftieth of it. Counts, samples and work are three separate
 * things now, and only the last of them decides anything.
 *
 * <p>
 * <b>What it does not do.</b> It never creates a catalogued file. Cataloguing is
 * what an inventory is, and two capabilities that both know how to bring a file
 * into the catalog is how they begin to disagree about what a catalogued file
 * is. What it does with files on disk that nobody knows is ask for an inventory
 * - which is the whole of the guarantee that a file appearing while its
 * notification is lost is still found, without waiting for a restart.
 */
@Slf4j
@Component
public class ReconcileConvergence {

	/**
	 * How many identifiers travel in one statement. Large enough that a library
	 * losing a whole drive is a handful of round trips, small enough to stay well
	 * inside the parameter ceiling of a single statement.
	 */
	private static final int BATCH_SIZE = 1_000;

	private final CatalogLifecycleWriter catalogLifecycleWriter;
	private final ExecutionEnqueueService executionEnqueueService;
	private final EligibilityAnnouncer eligibilityAnnouncer;
	private final ContentVerificationLauncher contentVerificationLauncher;
	private final Clock clock;

	public ReconcileConvergence(CatalogLifecycleWriter catalogLifecycleWriter,
			ExecutionEnqueueService executionEnqueueService,
			EligibilityAnnouncer eligibilityAnnouncer, ContentVerificationLauncher contentVerificationLauncher,
			Clock clock) {
		this.catalogLifecycleWriter = catalogLifecycleWriter;
		this.executionEnqueueService = executionEnqueueService;
		this.eligibilityAnnouncer = eligibilityAnnouncer;
		this.contentVerificationLauncher = contentVerificationLauncher;
		this.clock = clock;
	}

	@Transactional
	public OrganizationReconcileResponse apply(Scan scan) {
		OrganizationReconcileResponse response = scan.response();

		Instant observedAt = Instant.now(clock);

		int markedMissing = markMissing(scan.missingFiles().stream().map(MissingFile::catalogFileId).toList(),
				observedAt);

		// One admission for the pass, not one per kind of thing it found. Admission
		// holds a lock per identity until this transaction commits, and two passes
		// sharing files would deadlock if each took its locks in the order it happened
		// to find them - so the whole set is handed over at once, and ordered there.
		List<Execution> intents = new ArrayList<>(discoveryRequests(response, scan.physicalOnly()));

		intents.addAll(verificationRequests(scan.contentSuspects(), observedAt));

		if (!intents.isEmpty()) {
			executionEnqueueService.enqueueAll(intents);
		}

		if (markedMissing > 0) {
			// Files leaving the analysed set changes what every published similarity
			// grouping was computed over. A pass that agreed with the disk - nearly every
			// one of them - says nothing.
			eligibilityAnnouncer.announce("reconcile");
		}

		return withRepairs(response, markedMissing);
	}

	/**
	 * Every file the walk did not find, in batches, and not a sample of them.
	 *
	 * <p>
	 * Missing is a statement about the file system and nothing else: the row keeps
	 * its last known place, which is what lets the catalog look for it again and
	 * what a screen shows when asked where the file used to be. A file the user
	 * deliberately removed is a different state, decided elsewhere, and a pass over
	 * the disk has no business overwriting it.
	 */
	private int markMissing(List<Long> catalogFileIds, Instant observedAt) {
		if (catalogFileIds.isEmpty()) {
			return 0;
		}

		// A file the catalog can no longer find is something that happened to it, not
		// only a column that changed: looking at a photo marked missing months later,
		// the timeline is what says when it was noticed, what was looking and on what
		// grounds. The pass caused nothing - it found an empty path - so there is a
		// fact and no operation.
		CatalogFactProvenance provenance = new CatalogFactProvenance(observedAt, CatalogEventSources.RECONCILE,
				CatalogEventEvidence.PATH_NOT_FOUND, null);

		int marked = 0;

		for (int from = 0; from < catalogFileIds.size(); from += BATCH_SIZE) {
			marked += catalogLifecycleWriter.markMissing(
					catalogFileIds.subList(from, Math.min(from + BATCH_SIZE, catalogFileIds.size())), provenance);
		}

		log.info("Reconciliation marked {} catalogued file(s) missing out of the {} it could not find", marked,
				catalogFileIds.size());

		return marked;
	}

	/**
	 * Files that exist and nobody knows about, handed to the capability that
	 * catalogues them.
	 *
	 * <p>
	 * This is what turns the periodic pass from a report into a safety net. A file
	 * that appears while the watcher misses its notification, in a library where
	 * nothing else happens for days, used to stay invisible until somebody
	 * restarted the application: the walk counted it on every round and told nobody
	 * who could act on it. The request is one row, deduplicated on the folder, so a
	 * library that keeps finding the same unknown files does not accumulate work.
	 *
	 * <p>
	 * An inventory already waiting answers this; one already <em>running</em> does
	 * not, and the successor is the point. A walk that is under way has passed the
	 * folder it passed; a file this reconcile just found uncatalogued may well have
	 * appeared after it went by, and nothing would catalogue it until some later
	 * pass happened to look again.
	 *
	 * <p>
	 * It is asked before inserting rather than after being refused: this runs inside
	 * the transaction that applies the whole convergence, and a refusal by the
	 * deduplication index would mark that transaction rollback-only - losing every
	 * repair the pass had just made.
	 */
	private List<Execution> discoveryRequests(OrganizationReconcileResponse response, List<String> physicalOnly) {
		if (physicalOnly.isEmpty()) {
			return List.of();
		}

		Path source = PathUtils.normalizePath(response.sourcePath());

		log.info("Reconciliation found {} file(s) on disk that are not catalogued under {}; an inventory was queued",
				physicalOnly.size(), source);

		return List.of(Execution.builder().executionType(ExecutionType.INVENTORY)
				.triggerEvent(ExecutionTrigger.TIMER).sourcePath(PathUtils.normalize(source))
				.recursive(response.recursive()).executeFlag(true).dedupKey(OperationPathKey.canonical(source))
				.statusMessage(StatusMessage.code(ExecutionMessages.INVENTORY_STARTED)).build());
	}

	/**
	 * Files that are still where the catalog says, holding something the catalog
	 * may not have seen.
	 *
	 * <p>
	 * The pass does not read them. A digest is the only thing that settles whether
	 * the bytes differ, and reading a library of them on the thread that walks it
	 * would turn a comparison into a full re-hash of everything that was ever
	 * touched. What it does is ask, once per file, through the same durable
	 * verification the watcher uses - so a suspicion raised by a walk and one
	 * raised by a notification are answered by the same code, and coalesce with
	 * each other.
	 *
	 * <p>
	 * The instant is the pass own, and honestly so: a difference discovered by
	 * comparing today has no timestamp from the operating system behind it, and the
	 * moment it was observed is the truest thing that can be said about when it was
	 * noticed.
	 *
	 * <p>
	 * <b>The path is not optional.</b> A verification is excluded by the place it
	 * reads, so a request that names none cannot take its lock and is failed by the
	 * dispatcher before it is ever attempted - which left the divergence unconverged
	 * and the next pass finding it again, thousands of rows per turn. The suspect
	 * carries the place it sits precisely so this cannot be forgotten again.
	 */
	private List<Execution> verificationRequests(List<ContentSuspect> contentSuspects, Instant observedAt) {
		if (contentSuspects.isEmpty()) {
			return List.of();
		}

		log.info("Reconciliation asked for {} file(s) to have their content verified", contentSuspects.size());

		return contentVerificationLauncher.requestsFor(contentSuspects, observedAt, ExecutionTrigger.TIMER);
	}

	private OrganizationReconcileResponse withRepairs(OrganizationReconcileResponse response, int markedMissing) {
		return new OrganizationReconcileResponse(response.sourcePath(), response.recursive(), response.includeHidden(),
				response.filesOnDisk(), response.filesInDatabase(), response.missingOnDisk(),
				response.missingInDatabase(), response.missingOnDiskSamples(), response.missingInDatabaseSamples(),
				response.renamed(), 0, markedMissing, markedMissing + response.renamed());
	}
}