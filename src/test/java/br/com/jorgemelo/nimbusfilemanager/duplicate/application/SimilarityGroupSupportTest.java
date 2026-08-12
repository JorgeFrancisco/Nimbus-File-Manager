package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateCandidateFileResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Reason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Verdict;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SizeResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;

/**
 * The member order the analysis freezes, and why it is worth freezing.
 *
 * <p>
 * The keep goes first because that is how the result is read back: the screen
 * renders a published group without re-running the keep policy, over files whose
 * quality data may have changed since. If the order were not decided here, it
 * would have to be decided at every render, from data that no longer matches
 * what the analysis saw.
 */
class SimilarityGroupSupportTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.MAY, 1, 10, 0);
	/** When the file was last written to, which is a moment on the timeline. */
	private static final Instant WRITTEN_AT = Instant.parse("2026-05-01T10:00:00Z");

	@Test
	void theKeepIsFirstAndTheReviewCandidatesComeAfterTheDeleteCandidates() {
		DuplicateCandidateFileResponse keep = file("original.jpg", Verdict.KEEP, Reason.ORIGINAL);
		DuplicateCandidateFileResponse copy = file("copy.jpg", Verdict.DELETE_CANDIDATE, Reason.IDENTICAL_COPY);
		DuplicateCandidateFileResponse review = file("edited.jpg", Verdict.REVIEW, Reason.REVIEW_NO_CLEAR_ORIGINAL);

		AnalyzedGroup group = SimilarityGroupSupport.toAnalyzedGroup(96, 2048L, keep, List.of(copy), List.of(review));

		Assertions.assertThat(group.similarityPercent()).isEqualTo(96);
		Assertions.assertThat(group.wastedBytes()).isEqualTo(2048L);

		Assertions.assertThat(group.members()).extracting(AnalyzedMember::mediaPublicId).containsExactly(keep.id(),
				copy.id(), review.id());
		Assertions.assertThat(group.members()).extracting(AnalyzedMember::verdict).containsExactly(Verdict.KEEP,
				Verdict.DELETE_CANDIDATE, Verdict.REVIEW);
		Assertions.assertThat(group.members()).extracting(AnalyzedMember::reason).containsExactly(Reason.ORIGINAL,
				Reason.IDENTICAL_COPY, Reason.REVIEW_NO_CLEAR_ORIGINAL);
	}

	@Test
	void aGroupWithNothingToReviewCarriesOnlyTheKeepAndItsCandidates() {
		DuplicateCandidateFileResponse keep = file("original.jpg", Verdict.KEEP, Reason.ORIGINAL);

		AnalyzedGroup group = SimilarityGroupSupport.toAnalyzedGroup(100, 0L, keep, List.of(), List.of());

		Assertions.assertThat(group.members()).hasSize(1);
	}

	private DuplicateCandidateFileResponse file(String name, Verdict verdict, Reason reason) {
		return new DuplicateCandidateFileResponse(UUID.randomUUID(), name, "jpg", "PHOTO", SizeResponse.of(1024),
				"C:/fotos/" + name, "C:/fotos", WRITTEN_AT, verdict, reason, 1920, 1080, NOW, DateSource.EXIF);
	}
}