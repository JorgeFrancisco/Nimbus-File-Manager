package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaQualityRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityRelationRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.CompositionRow;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoHashRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.RelationRow;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence.SimilarityRelationWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

/**
 * Answering from the relations already approved, which is what a removal costs.
 *
 * <p>
 * Synthetic and deterministic on purpose. What these tests are about is which
 * groups come out of a given set of relations, and that question has nothing to
 * do with real photographs: a library would make the same assertions slower,
 * less legibly and only on the machine that has it.
 */
class PhotoSimilarityRegroupTest {

	private static final int MINIMUM = 95;

	private static final SimilarityProgressCallback SILENT = (_, _) -> {
	};

	private final MediaFingerprintRepository repository = mock(MediaFingerprintRepository.class);
	private final MediaQualityRepository mediaQualityRepository = mock(MediaQualityRepository.class);
	private final SimilarityRelationWriter relationWriter = mock(SimilarityRelationWriter.class);
	private final SimilarityRelationRepository relationRepository = mock(SimilarityRelationRepository.class);
	private final DuplicateExclusionService exclusions = mock(DuplicateExclusionService.class);

	private final PhotoSimilarityService service = new PhotoSimilarityService(repository,
			new DuplicateGroupAssembler(new DuplicateKeepPolicy(), mediaQualityRepository), new LuminanceSsimService(),
			exclusions, relationWriter, relationRepository);

	@BeforeEach
	void defaults() {
		when(exclusions.signature()).thenReturn("none");
		when(mediaQualityRepository.findByPublicIdIn(any())).thenReturn(List.of());
	}

	/**
	 * <b>The counterexample, taken through the code that answers it.</b> A relates
	 * to B and to C, and B and C do not relate to each other. Visited in order, B
	 * joins A and C is refused - because complete linkage requires every member,
	 * and B is a member.
	 *
	 * <p>
	 * Then B leaves. A rebuild would put A and C together, and so must this: the
	 * refusal C suffered was caused by a file that is gone, and only running the
	 * placement again can take it back. Editing the published group - dropping B
	 * and keeping the rest - would leave A alone forever with C beside it,
	 * unrelated on paper and identical in fact.
	 */
	@Test
	void removingAMemberLetsTheRefusedCandidateFormTheGroupItCouldNotJoin() {
		eligible(1L, 2L, 3L);
		stored(StoredRelationRow.approved(1L, 2L, 96), StoredRelationRow.approved(1L, 3L, 96));
		loaded(1L, 2L, 3L);

		assertThat(groupsOf(service.regroup(MINIMUM, SILENT))).as("B joined A, and C was refused because of B")
				.containsExactly(Set.of(publicId(1L), publicId(2L)));

		// B leaves: it stops being eligible, so the query stops returning either the
		// file or the relations that name it. Nothing is deleted and nothing is
		// recomputed.
		eligible(1L, 3L);
		stored(StoredRelationRow.approved(1L, 3L, 96));
		loaded(1L, 3L);

		assertThat(groupsOf(service.regroup(MINIMUM, SILENT))).as("the group the local update could never find")
				.containsExactly(Set.of(publicId(1L), publicId(3L)));
	}

	/**
	 * Nothing is compared. The relations are the answer to "does this pair reach
	 * the threshold", and a regroup reads them - so no fingerprint is scanned, no
	 * SSIM is run, and nothing is written back to the relation table either.
	 */
	@Test
	void aRegroupApprovesNothingAndThereforeWritesNothing() {
		eligible(1L, 2L);
		stored(StoredRelationRow.approved(1L, 2L, 96));
		loaded(1L, 2L);

		service.regroup(MINIMUM, SILENT);

		verifyNoInteractions(relationWriter);
	}

	/**
	 * Only the files that take part in a relation are loaded, and the groups are
	 * the same as if the whole library had been visited.
	 *
	 * <p>
	 * A file with no approved neighbour can only form a group of one, which is
	 * never published, and it can never be the reason another candidate was
	 * refused - the placement only ever examines clusters holding a neighbour. So
	 * the eight rows nobody relates to are eight rows nobody needs to read, which
	 * is what makes a regroup proportional to the relations rather than to the
	 * library.
	 */
	@Test
	void filesThatTakePartInNoRelationAreNeverLoaded() {
		eligible(1L, 2L, 3L, 4L, 5L);
		stored(StoredRelationRow.approved(2L, 4L, 97));
		loaded(2L, 4L);

		assertThat(groupsOf(service.regroup(MINIMUM, SILENT)))
				.containsExactly(Set.of(publicId(2L), publicId(4L)));

		verify(repository).findFingerprintedPhotos(any(), any(), eq(List.of(2L, 4L)));
	}

	/**
	 * The composition still names every eligible file, not only the grouped ones.
	 * It is the account of what the analysis was about, and a file that matched
	 * nothing was examined and found to match nothing - the same statement a
	 * rebuild makes about it.
	 */
	@Test
	void theCompositionNamesEveryEligibleFileAndNotOnlyTheGroupedOnes() {
		eligible(1L, 2L, 3L, 4L, 5L);
		stored(StoredRelationRow.approved(2L, 4L, 97));
		loaded(2L, 4L);

		SimilarityAnalysisResult result = service.regroup(MINIMUM, SILENT);

		assertThat(result.composition().analyzedCount()).isEqualTo(5);
		assertThat(result.composition().eligibleCount()).isEqualTo(5);
		assertThat(result.family()).isEqualTo(service.family(MINIMUM));
	}

	/**
	 * A file hidden from comparison after the request takes its relations out of
	 * the grouping without any of them being deleted - which is the whole reason
	 * exclusion and deletion are different things here. Un-hiding it later reuses
	 * what was already computed.
	 */
	@Test
	void aFileHiddenAfterTheRequestLeavesTheGroupingWithoutLosingItsRelations() {
		eligible(1L, 2L, 3L);
		stored(StoredRelationRow.approved(1L, 2L, 96), StoredRelationRow.approved(1L, 3L, 96),
				StoredRelationRow.approved(2L, 3L, 96));
		loaded(1L, 2L, 3L);

		when(exclusions.excludedFilePublicIds()).thenReturn(List.of(publicId(2L)));

		assertThat(groupsOf(service.regroup(MINIMUM, SILENT)))
				.containsExactly(Set.of(publicId(1L), publicId(3L)));

		verifyNoInteractions(relationWriter);
	}

	/** No relations stored for these parameters: no groups, and no failure. */
	@Test
	void aFamilyWithNoStoredRelationsProducesNoGroups() {
		eligible(1L, 2L);
		stored();
		loaded();

		SimilarityAnalysisResult result = service.regroup(MINIMUM, SILENT);

		assertThat(result.groups()).isEmpty();
		assertThat(result.composition().analyzedCount()).isEqualTo(2);
	}

	private List<Set<UUID>> groupsOf(SimilarityAnalysisResult result) {
		return result.groups().stream().map(group -> group.members().stream().map(AnalyzedMember::mediaPublicId)
				.collect(Collectors.toSet())).toList();
	}

	private void eligible(Long... ids) {
		// any() and not anyInt(): photos pass a null limit, which is how the query is
		// told there is no cap, and anyInt() would not match it.
		when(repository.findEligibleForSimilarity(any(), any())).thenReturn(List.of(ids));
		when(repository.countEligibleForSimilarity(any(), any())).thenReturn(ids.length);
		when(repository.findPhotoCompositionRows(any(), any(), any())).thenReturn(
				Arrays.stream(ids).map(id -> new CompositionRow(publicId(id), "C:/Fotos")).toList());
	}

	private void stored(RelationRow... rows) {
		when(relationRepository.findEligibleRelations(any(), anyInt(), anyInt(), any(), any()))
				.thenReturn(List.of(rows));
	}

	/**
	 * What the heavy query returns, in {@code catalog_file.id} order - which is
	 * what the query promises and what the positions the relations are indexed by
	 * depend on.
	 */
	private void loaded(Long... ids) {
		when(repository.findFingerprintedPhotos(any(), any(), any()))
				.thenReturn(Arrays.stream(ids).map(this::photo).toList());
	}

	private PhotoHashRawResponse photo(Long id) {
		return new PhotoHashRawResponse(id, new byte[32], new byte[1024], id + ".jpg", "jpg", 100L,
				"C:/Fotos/" + id + ".jpg", "C:/Fotos", LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0).plusDays(id));
	}

	private UUID publicId(Long catalogFileId) {
		return UuidV7.fromLegacy(catalogFileId);
	}
}