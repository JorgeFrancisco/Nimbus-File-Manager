package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.FingerprintAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.SimilarityConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityComposition;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityFamily;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.GroupingStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Reason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Verdict;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGrouping;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityGroupingRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence.SimilarityGroupingWriter;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;

/**
 * Publication is split in two on purpose: a long transaction that writes the
 * whole result while it is still invisible, and a short one that makes it the
 * answer. These tests hold that split - what is written is BUILDING, and only
 * the second step retires the previous ACTIVE.
 */
class SimilarityPublisherTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.MAY, 1, 10, 0);
	private static final String PARAMETERS = "p".repeat(64);
	private static final String COMPOSITION = "c".repeat(64);

	private final SimilarityGroupingRepository groupingRepository = mock(SimilarityGroupingRepository.class);
	private final SimilarityGroupingWriter groupingWriter = mock(SimilarityGroupingWriter.class);
	private final Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

	private final SimilarityPublisher publisher = new SimilarityPublisher(groupingRepository, groupingWriter, clock);

	@BeforeEach
	void defaults() {
		when(groupingRepository.saveAndFlush(any())).thenAnswer(call -> {
			SimilarityGrouping grouping = call.getArgument(0);
			grouping.setId(1L);
			grouping.setSimilarityGroupingPublicId(UUID.randomUUID());
			return grouping;
		});
	}

	@Test
	void whatIsWrittenIsInvisibleUntilItIsPublished() {
		ArgumentCaptor<SimilarityGrouping> saved = ArgumentCaptor.forClass(SimilarityGrouping.class);

		publisher.build(result(group(96, 2048L, 2)), 42L);

		verify(groupingRepository).saveAndFlush(saved.capture());

		Assertions.assertThat(saved.getValue().getStatus()).isEqualTo(GroupingStatus.BUILDING);
		Assertions.assertThat(saved.getValue().getExecutionId()).isEqualTo(42L);
		Assertions.assertThat(saved.getValue().getComputedAt()).isEqualTo(NOW);
		Assertions.assertThat(saved.getValue().getGroupCount()).isEqualTo(1);
		Assertions.assertThat(saved.getValue().getMemberCount()).isEqualTo(2);

		verify(groupingRepository, never()).supersedeActive(any(), any(), anyInt(), any());
		verify(groupingRepository, never()).publish(any(), any());
	}

	/**
	 * The header is written here and the bulk is handed to the writer, under the id
	 * the header just got. What the rows end up looking like is the writer's own
	 * business and is held against a real database by
	 * {@code SimilarityGroupingWriterIntegrationTest} - a mock cannot show a
	 * position, a foreign key or a batch.
	 */
	@Test
	void theGroupsAreHandedToTheWriterUnderTheHeaderThatWasJustCreated() {
		ArgumentCaptor<List<AnalyzedGroup>> handed = ArgumentCaptor.captor();

		SimilarityAnalysisResult result = result(group(96, 2048L, 2), group(80, 512L, 3));

		publisher.build(result, 42L);

		verify(groupingWriter).write(eq(1L), handed.capture());

		Assertions.assertThat(handed.getValue()).isEqualTo(result.groups());
	}

	@Test
	void publishingRetiresThePreviousAnswerAndMakesThisOneTheAnswer() {
		when(groupingRepository.supersedeActive(any(), any(), anyInt(), any())).thenReturn(1);
		when(groupingRepository.publish(eq(1L), any())).thenReturn(1);

		Assertions.assertThat(publisher.publish(built(), Takings.unfenced(1L))).isTrue();

		verify(groupingRepository).supersedeActive(FileType.PHOTO, FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1,
				SimilarityConstants.GROUPING_VERSION, PARAMETERS);
		verify(groupingRepository).publish(1L, NOW);
	}

	@Test
	void aGroupingThatIsNoLongerBuildingIsNotPublished() {
		when(groupingRepository.supersedeActive(any(), any(), anyInt(), any())).thenReturn(0);
		when(groupingRepository.publish(eq(1L), any())).thenReturn(0);

		Assertions.assertThat(publisher.publish(built(), Takings.unfenced(1L))).isFalse();
	}

	private SimilarityGrouping built() {
		return SimilarityGrouping.builder().id(1L).similarityGroupingPublicId(UUID.randomUUID()).mediaType(FileType.PHOTO)
				.algorithmId(FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1)
				.groupingVersion(SimilarityConstants.GROUPING_VERSION).parametersDigest(PARAMETERS)
				.compositionDigest(COMPOSITION).eligibleCount(120).analyzedCount(120).candidateLimit(8000)
				.selectionPolicy(SimilarityConstants.SELECTION_OLDEST_ELIGIBLE_FIRST).status(GroupingStatus.BUILDING)
				.groupCount(1).memberCount(2).build();
	}

	private SimilarityAnalysisResult result(AnalyzedGroup... groups) {
		return new SimilarityAnalysisResult(
				new SimilarityFamily(FileType.PHOTO, FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1,
						SimilarityConstants.GROUPING_VERSION, PARAMETERS),
				new SimilarityComposition(COMPOSITION, 120, 120, 8000,
						SimilarityConstants.SELECTION_OLDEST_ELIGIBLE_FIRST),
				List.of(groups));
	}

	private AnalyzedGroup group(int similarityPercent, long wastedBytes, int members) {
		List<AnalyzedMember> analyzed = new ArrayList<>();

		analyzed.add(new AnalyzedMember(UUID.randomUUID(), Verdict.KEEP, Reason.ORIGINAL));

		for (int index = 1; index < members; index++) {
			analyzed.add(new AnalyzedMember(UUID.randomUUID(), Verdict.DELETE_CANDIDATE, Reason.IDENTICAL_COPY));
		}

		return new AnalyzedGroup(similarityPercent, wastedBytes, analyzed);
	}
}