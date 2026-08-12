package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import java.time.Instant;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentAssessment;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentState;
import br.com.jorgemelo.nimbusfilemanager.catalog.domain.enums.ContentVerdict;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.FilesystemIdentity;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FilesystemIdentityKind;

/**
 * What a look at a file means, before anything is written.
 *
 * <p>
 * Two questions are answered here and they are not the same one. What the file
 * <em>contains</em> is settled by the digest when there is one, and only guessed
 * at from size and timestamp when there is not - which is why the honest answer
 * without a digest is that somebody has to read the file, not that it changed.
 * Which <em>object</em> holds the path is a separate matter entirely: an editor
 * that saves by writing a temporary file and renaming it over the original
 * leaves identical bytes behind a brand new object, and a catalog that conflated
 * the two would call that a content change.
 */
class ContentChangeDetectorTest {

	private static final String KNOWN = "a".repeat(64);
	private static final String DIFFERENT = "b".repeat(64);
	private static final Instant SEEN_AT = Instant.parse("2026-08-14T06:00:00Z");

	private final ContentChangeDetector detector = new ContentChangeDetector();

	@Test
	void aDigestThatAgreesSettlesItEvenWhenTheTimestampMoved() {
		ContentAssessment assessment = detector.assess(state(KNOWN, 1024L, SEEN_AT, null),
				state(KNOWN, 1024L, SEEN_AT.plusSeconds(3600), null));

		Assertions.assertThat(assessment.verdict()).isEqualTo(ContentVerdict.UNCHANGED);
		Assertions.assertThat(assessment.physicallyReplaced()).isFalse();
	}

	@Test
	void aDigestThatDiffersIsAChangeOfContent() {
		ContentAssessment assessment = detector.assess(state(KNOWN, 1024L, SEEN_AT, null),
				state(DIFFERENT, 1024L, SEEN_AT, null));

		Assertions.assertThat(assessment.verdict()).isEqualTo(ContentVerdict.CONTENT_CHANGED);
	}

	/**
	 * Hashing is opt-in on a scan, so a catalog holds files nobody has read. The
	 * first digest is knowledge gained about the same bytes - there is no previous
	 * content for it to differ from.
	 */
	@Test
	void theFirstDigestForAFileIsLearnedRatherThanComparedTo() {
		Assertions.assertThat(detector.assess(state(null, 1024L, SEEN_AT, null), state(KNOWN, 1024L, SEEN_AT, null))
				.verdict()).isEqualTo(ContentVerdict.HASH_LEARNED);
	}

	@Test
	void anObserverThatBroughtNoDigestAndFoundNothingDifferentSettlesIt() {
		Assertions.assertThat(detector.assess(state(KNOWN, 1024L, SEEN_AT, null), state(null, 1024L, SEEN_AT, null))
				.verdict()).as("the cheap facts agree, and there is nothing to suspect")
				.isEqualTo(ContentVerdict.UNCHANGED);
	}

	/**
	 * Size or timestamp moved and nobody read the file. That is a reason to read
	 * it, and not by itself a reason to say the content changed: a backup tool
	 * touching a file moves its timestamp without touching a byte.
	 */
	@Test
	void aDescriptionThatMovedWithoutADigestAsksForTheFileToBeRead() {
		Assertions.assertThat(detector.assess(state(KNOWN, 1024L, SEEN_AT, null), state(null, 2048L, SEEN_AT, null))
				.verdict()).isEqualTo(ContentVerdict.NEEDS_VERIFICATION);

		Assertions.assertThat(detector
				.assess(state(KNOWN, 1024L, SEEN_AT, null), state(null, 1024L, SEEN_AT.plusSeconds(60), null))
				.verdict()).isEqualTo(ContentVerdict.NEEDS_VERIFICATION);
	}

	@Test
	void aDifferentObjectAtTheSamePathIsAReplacementWhateverTheBytesSay() {
		ContentAssessment sameBytes = detector.assess(state(KNOWN, 1024L, SEEN_AT, identity("11")),
				state(KNOWN, 1024L, SEEN_AT, identity("22")));

		Assertions.assertThat(sameBytes.physicallyReplaced()).as("saved by writing a temporary file over the original")
				.isTrue();
		Assertions.assertThat(sameBytes.verdict()).as("the bytes are the ones on record, whatever holds them")
				.isEqualTo(ContentVerdict.UNCHANGED);

		ContentAssessment otherBytes = detector.assess(state(KNOWN, 1024L, SEEN_AT, identity("11")),
				state(DIFFERENT, 1024L, SEEN_AT, identity("22")));

		Assertions.assertThat(otherBytes.physicallyReplaced()).isTrue();
		Assertions.assertThat(otherBytes.verdict()).isEqualTo(ContentVerdict.CONTENT_CHANGED);
	}

	/**
	 * An observer that could not name an identity says nothing about which object
	 * is there - and silence is not evidence of a replacement. Asserting one would
	 * invalidate every fingerprint of a file that merely moved between file
	 * systems that answer this question differently.
	 */
	@Test
	void anIdentityNobodyNamedIsNotEvidenceOfAnything() {
		Assertions.assertThat(detector.assess(state(KNOWN, 1024L, SEEN_AT, identity("11")),
				state(KNOWN, 1024L, SEEN_AT, null)).physicallyReplaced()).isFalse();

		Assertions.assertThat(detector.assess(state(KNOWN, 1024L, SEEN_AT, null),
				state(KNOWN, 1024L, SEEN_AT, identity("22"))).physicallyReplaced()).isFalse();

		Assertions.assertThat(detector.assess(state(KNOWN, 1024L, SEEN_AT, identity("11")),
				state(KNOWN, 1024L, SEEN_AT, identity("11"))).physicallyReplaced())
				.as("the same object is not a replacement").isFalse();
	}

	/**
	 * The two dimensions meeting: no digest, nothing else moved, and a different
	 * object holding the path. The bytes are unaccounted for, so this is a file to
	 * read rather than a file to trust.
	 */
	@Test
	void aReplacementWithoutADigestAsksForTheFileToBeRead() {
		Assertions.assertThat(detector.assess(state(KNOWN, 1024L, SEEN_AT, identity("11")),
				state(null, 1024L, SEEN_AT, identity("22"))).verdict())
				.isEqualTo(ContentVerdict.NEEDS_VERIFICATION);
	}

	private ContentState state(String sha256, Long sizeBytes, Instant modifiedAt, FilesystemIdentity identity) {
		return new ContentState(sha256, sizeBytes, modifiedAt, identity);
	}

	private FilesystemIdentity identity(String value) {
		return new FilesystemIdentity(FilesystemIdentityKind.WINDOWS_FILE_ID, "volume-under-test", value);
	}
}