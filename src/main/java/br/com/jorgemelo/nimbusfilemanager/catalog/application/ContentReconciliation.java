package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import java.time.Instant;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentAssessment;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentObservation;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentState;
import br.com.jorgemelo.nimbusfilemanager.catalog.domain.enums.ContentOutcome;
import br.com.jorgemelo.nimbusfilemanager.catalog.infrastructure.persistence.ContentChangeWriter;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.EligibilityAnnouncer;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.CatalogFactProvenance;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.FilesystemIdentity;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;
import lombok.extern.slf4j.Slf4j;

/**
 * Where the catalog changes its mind about what a file contains - once, for
 * everybody.
 *
 * <p>
 * The watcher, a walk of the library and a secure move all end up asking the
 * same question, and they used to be in a position to answer it differently.
 * They do not decide anything here: the detector classifies, the database
 * arbitrates who got there first, and this puts the consequences of that in one
 * transaction.
 *
 * <p>
 * <b>What the transaction is for.</b> A catalog saying a file holds new bytes,
 * with a fingerprint computed from the old ones still on record, is not a
 * catalog that is behind - it is one that is wrong, and every screen reading it
 * would be wrong with it. So the digest, the generation counter and the removal
 * of everything derived from the previous generation commit together or not at
 * all.
 *
 * <p>
 * <b>What it will not do.</b> Learning a digest for a file that had none proves
 * nothing happened, so it invalidates nothing and moves no counter. And a
 * reconciliation never decides on cheap signals: it is given a digest or it
 * refuses, because a size and a timestamp can move without the bytes changing
 * and the bytes can change without either of them moving.
 */
@Slf4j
@Service
public class ContentReconciliation {

	private final ContentChangeDetector contentChangeDetector;
	private final ContentChangeWriter contentChangeWriter;
	private final ContentMetadataRebuild contentMetadataRebuild;
	private final EligibilityAnnouncer eligibilityAnnouncer;

	public ContentReconciliation(ContentChangeDetector contentChangeDetector,
			ContentChangeWriter contentChangeWriter, ContentMetadataRebuild contentMetadataRebuild,
			EligibilityAnnouncer eligibilityAnnouncer) {
		this.contentChangeDetector = contentChangeDetector;
		this.contentChangeWriter = contentChangeWriter;
		this.contentMetadataRebuild = contentMetadataRebuild;
		this.eligibilityAnnouncer = eligibilityAnnouncer;
	}

	/**
	 * @param file the catalogued file as the caller last read it, whose revision
	 * is what the database checks the observation against
	 * @throws IllegalArgumentException when the observation carries no digest.
	 * Reconciling is settling what a file contains, and nothing short of reading
	 * it settles that - a caller that has not paid for the read wants a
	 * verification, not this
	 */
	@Transactional
	public ContentOutcome reconcile(CatalogFile file, ContentObservation observation) {
		ContentState observed = observation.observed();

		if (observed.sha256() == null) {
			throw new IllegalArgumentException("A content reconciliation needs a digest to reconcile against");
		}

		ContentState known = knownStateOf(file);

		ContentAssessment assessment = contentChangeDetector.assess(known, observed);

		return switch (assessment.verdict()) {
		case CONTENT_CHANGED -> applyChange(file, known, observation);
		case HASH_LEARNED -> learn(file, observation);
		// A digest that agrees settles it. The object holding the path may still be
		// a new one - an atomic replace with identical bytes - and that is worth
		// recording, because it is what the next observation will be compared to.
		case UNCHANGED -> settle(file, observation, assessment);
		// Unreachable: the detector only asks for verification when it has no
		// digest, and the guard above refused that already.
		case NEEDS_VERIFICATION -> ContentOutcome.STALE_OBSERVATION;
		};
	}

	/**
	 * The same settlement, for a digest that was produced as a side effect of doing
	 * something else.
	 *
	 * <p>
	 * A secure move reads the file twice to prove the copy matches the original, and
	 * that proof is about the current bytes whether or not anyone asked. Feeding it
	 * here is what turns work already paid for into knowledge: a file the catalog
	 * had never hashed gets its digest, and one whose digest no longer matches is
	 * discovered to have been stale - which the move did not cause and does not
	 * make untrue.
	 *
	 * <p>
	 * Transactional in its own right, and not because its callers happen to be:
	 * what it reaches is the same all-or-nothing write above, so the door has to
	 * declare the boundary rather than inherit one by convention. Every caller
	 * today arrives holding a transaction and joins this one.
	 *
	 * @param sha256 the digest the operation proved
	 */
	@Transactional
	public ContentOutcome reconcileFromDigest(CatalogFile file, String sha256, Long sizeBytes, String source,
			Instant occurredAt) {
		if (sha256 == null) {
			return ContentOutcome.ALREADY_CONVERGED;
		}

		return reconcile(file, new ContentObservation(new ContentState(sha256, sizeBytes, null, null), source,
				occurredAt));
	}

	private ContentOutcome applyChange(CatalogFile file, ContentState known, ContentObservation observation) {
		CatalogFactProvenance provenance = new CatalogFactProvenance(observation.occurredAt(), observation.source(),
				CatalogEventEvidence.CONTENT_DIGEST_CHANGED, observation.observed().identity());

		ContentOutcome outcome = contentChangeWriter.applyContentChange(file.getId(), file.getContentRevision(),
				known, observation.observed(), UuidV7.generate(), provenance);

		if (outcome != ContentOutcome.APPLIED) {
			// Somebody else got there, or the world moved under the observation. In
			// neither case is there anything of ours to clear: the derived state now
			// on record belongs to whatever generation won.
			log.debug("Content change for catalog file {} was not applied: {}", file.getId(), outcome);

			return outcome;
		}

		contentChangeWriter.clearDerivedState(file.getId());

		// Fingerprints come back on their own - the backlog looks for files that have
		// none. Metadata does not: nothing goes looking for a missing one, so leaving
		// the gap would mean a photo without dimensions or a capture date until
		// somebody rebuilt a folder by hand. The work is queued inside this
		// transaction, so a change that rolls back takes the request with it.
		contentMetadataRebuild.rebuild(file, ExecutionTrigger.FILE_EVENT);

		// Losing its fingerprint takes the file out of the set the similarity
		// algorithm runs over, which changes the composition every published grouping
		// was computed from. Saying so is what makes the regrouping prompt instead of
		// waiting for the sweeper to notice on its own round.
		eligibilityAnnouncer.announce("content change");

		log.info("Catalog file {} holds different bytes than recorded; its derived state was cleared and a rebuild "
				+ "was queued", file.getId());

		return outcome;
	}

	/**
	 * A file nobody had hashed has no previous content to differ from, so this
	 * records what it holds and touches nothing else. The identity is recorded
	 * with it when one was observed, which is how a location comes to carry one
	 * without anybody opening a library's worth of files to ask.
	 */
	private ContentOutcome learn(CatalogFile file, ContentObservation observation) {
		String learned = contentChangeWriter.learnDigest(file.getId(), observation.observed());

		contentChangeWriter.recordFilesystemIdentity(file.getId(), observation.observed().identity());

		return switch (learned) {
		case "LEARNED" -> ContentOutcome.APPLIED;
		case "ALREADY_KNOWN" -> ContentOutcome.ALREADY_CONVERGED;
		// Another worker learned a different digest between our read and our write,
		// which is not something to settle by writing over it.
		default -> ContentOutcome.CONFLICT;
		};
	}

	private ContentOutcome settle(CatalogFile file, ContentObservation observation, ContentAssessment assessment) {
		if (assessment.physicallyReplaced()) {
			contentChangeWriter.recordFilesystemIdentity(file.getId(), observation.observed().identity());

			log.debug("Catalog file {} is held by a different physical object with the same bytes", file.getId());
		}

		// The digest settled that these are the same bytes, and the cheap facts still
		// say otherwise - a timestamp moved without the content moving. Recording what
		// was seen is what stops the next walk from suspecting this file again and
		// paying for the same reading, pass after pass, forever.
		if (describedDifferently(file, observation.observed())) {
			contentChangeWriter.recordObservedFacts(file.getId(), file.getContentRevision(), observation.observed());

			log.debug("Catalog file {} holds the bytes on record, described differently on disk", file.getId());
		}

		return ContentOutcome.ALREADY_CONVERGED;
	}

	private static boolean describedDifferently(CatalogFile file, ContentState observed) {
		if (observed.sizeBytes() == null || observed.modifiedAt() == null) {
			// A caller that arrived with a digest and nothing else did not look at how the
			// file describes itself - a verified move proves the bytes and never stats the
			// destination - and what it did not look at must not overwrite what somebody
			// who did look recorded.
			return false;
		}

		return !Objects.equals(file.getSizeBytes(), observed.sizeBytes())
				|| !Objects.equals(file.getModifiedAt(), observed.modifiedAt());
	}

	private static ContentState knownStateOf(CatalogFile file) {
		CatalogFileLocation location = file.getLocation();

		FilesystemIdentity identity = location == null || location.getFilesystemIdentityValue() == null ? null
				: new FilesystemIdentity(location.getFilesystemIdentityKind(), location.getFilesystemIdentityScope(),
						location.getFilesystemIdentityValue());

		Instant modifiedAt = file.getModifiedAt();

		return new ContentState(file.getSha256(), file.getSizeBytes(), modifiedAt, identity);
	}
}