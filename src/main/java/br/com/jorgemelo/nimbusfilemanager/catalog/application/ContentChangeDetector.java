package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import java.util.Objects;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentAssessment;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentState;
import br.com.jorgemelo.nimbusfilemanager.catalog.domain.enums.ContentVerdict;

/**
 * The one place that decides whether a file still holds the bytes the catalog
 * says it does.
 *
 * <p>
 * One place because the question is the same wherever it is asked. The watcher
 * asks it when the operating system reports a write, a full scan asks it about
 * every file it walks past, and a reconciliation asks it about what it found on
 * disk - and if each answered it their own way, the catalog would hold three
 * different opinions about what "current" means. What differs between them is
 * how much they are willing to pay for the answer, not what the answer means.
 *
 * <p>
 * <b>It reads nothing.</b> No file is opened here and no digest computed: it is
 * given what the caller already knows and returns a verdict, which is what lets
 * the watcher call it on its poll thread without a large file stalling every
 * observation. When the cheap facts cannot settle the question it says so, and
 * paying for a read is then the caller's decision to make where it can afford
 * it.
 *
 * <p>
 * <b>The order of the questions is the point.</b> A digest, when there is one,
 * outranks everything: it is the only evidence that speaks about the bytes
 * themselves. Size and modification time are asked only when there is no digest,
 * and only ever to raise a suspicion - they can move without the content
 * changing, and the content can change without either of them moving.
 */
@Service
public class ContentChangeDetector {

	/**
	 * @param known what the catalog holds
	 * @param observed what was just seen, whose digest may be absent when the
	 * look was a cheap one
	 */
	public ContentAssessment assess(ContentState known, ContentState observed) {
		boolean replaced = replaced(known, observed);

		if (observed.sha256() == null) {
			return new ContentAssessment(withoutDigest(known, observed, replaced), replaced);
		}

		if (known.sha256() == null) {
			return new ContentAssessment(ContentVerdict.HASH_LEARNED, replaced);
		}

		return new ContentAssessment(known.sha256().equals(observed.sha256()) ? ContentVerdict.UNCHANGED
				: ContentVerdict.CONTENT_CHANGED, replaced);
	}

	/**
	 * Whether the thing at that path is a different thing. Only answerable when
	 * both sides named an identity: a source that supplies none says nothing about
	 * this, and absence is not evidence of sameness any more than of difference.
	 */
	private boolean replaced(ContentState known, ContentState observed) {
		return known.identity() != null && observed.identity() != null
				&& !known.identity().equals(observed.identity());
	}

	/**
	 * Without a digest the cheap facts are all there is, and they can only raise a
	 * suspicion. A swapped object raises one on its own - the bytes at that path
	 * were written by something else, whether or not they came out the same.
	 */
	private ContentVerdict withoutDigest(ContentState known, ContentState observed, boolean replaced) {
		if (replaced || !Objects.equals(known.sizeBytes(), observed.sizeBytes())
				|| !Objects.equals(known.modifiedAt(), observed.modifiedAt())) {
			return ContentVerdict.NEEDS_VERIFICATION;
		}

		return ContentVerdict.UNCHANGED;
	}
}