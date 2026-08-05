package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

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
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGroupMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGrouping;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityGroupMemberRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityGroupRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityGroupingRepository;
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
	private final SimilarityGroupRepository groupRepository = mock(SimilarityGroupRepository.class);
	private final SimilarityGroupMemberRepository memberRepository = mock(SimilarityGroupMemberRepository.class);
	private final Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

	private final SimilarityPublisher publisher = new SimilarityPublisher(groupingRepository, groupRepository,
			memberRepository, clock);

	@BeforeEach
	void defaults() {
		when(groupingRepository.save(any())).thenAnswer(call -> {
			SimilarityGrouping grouping = call.getArgument(0);
			grouping.setId(1L);
			grouping.setPublicId(UUID.randomUUID());
			return grouping;
		});

		AtomicLong nextGroupId = new AtomicLong(100);

		when(groupRepository.saveAll(any())).thenAnswer(call -> {
			List<SimilarityGroup> rows = call.getArgument(0);
			rows.forEach(row -> row.setId(nextGroupId.getAndIncrement()));
			return new ArrayList<>(rows);
		});
		when(memberRepository.saveAll(any())).thenAnswer(call -> new ArrayList<>(call.getArgument(0)));
	}

	@Test
	void whatIsWrittenIsInvisibleUntilItIsPublished() {
		ArgumentCaptor<SimilarityGrouping> saved = ArgumentCaptor.forClass(SimilarityGrouping.class);

		publisher.build(result(group(96, 2048L, 2)), 42L);

		verify(groupingRepository).save(saved.capture());

		Assertions.assertThat(saved.getValue().getStatus()).isEqualTo(GroupingStatus.BUILDING);
		Assertions.assertThat(saved.getValue().getExecutionId()).isEqualTo(42L);
		Assertions.assertThat(saved.getValue().getComputedAt()).isEqualTo(NOW);
		Assertions.assertThat(saved.getValue().getGroupCount()).isEqualTo(1);
		Assertions.assertThat(saved.getValue().getMemberCount()).isEqualTo(2);

		verify(groupingRepository, never()).supersedeActive(any(), any(), anyInt(), any());
		verify(groupingRepository, never()).publish(any(), any());
	}

	@Test
	void everyGroupAndMemberKeepsThePositionTheAnalysisGaveIt() {
		ArgumentCaptor<List<SimilarityGroup>> groups = ArgumentCaptor.captor();
		ArgumentCaptor<List<SimilarityGroupMember>> members = ArgumentCaptor.captor();

		publisher.build(result(group(96, 2048L, 2), group(80, 512L, 3)), 42L);

		verify(groupRepository).saveAll(groups.capture());
		verify(memberRepository).saveAll(members.capture());

		Assertions.assertThat(groups.getValue()).extracting(SimilarityGroup::getPosition).containsExactly(0, 1);
		Assertions.assertThat(groups.getValue()).extracting(SimilarityGroup::getFileCount).containsExactly(2, 3);

		Assertions.assertThat(members.getValue()).extracting(SimilarityGroupMember::getGroupId)
				.containsExactly(100L, 100L, 101L, 101L, 101L);
		Assertions.assertThat(members.getValue()).extracting(SimilarityGroupMember::getPosition)
				.containsExactly(0, 1, 0, 1, 2);
		Assertions.assertThat(members.getValue().getFirst().getVerdict()).isEqualTo(Verdict.KEEP);
	}

	@Test
	void aResultLargerThanOneBatchIsWrittenInSeveralRoundTrips() {
		List<AnalyzedGroup> many = new ArrayList<>();

		for (int index = 0; index < 501; index++) {
			many.add(group(90, 1L, 2));
		}

		publisher.build(result(many.toArray(AnalyzedGroup[]::new)), 42L);

		// 501 groups is two batches; the 1002 members they hold are three.
		verify(groupRepository, times(2)).saveAll(any());
		verify(memberRepository, times(3)).saveAll(any());
	}

	@Test
	void publishingRetiresThePreviousAnswerAndMakesThisOneTheAnswer() {
		when(groupingRepository.supersedeActive(any(), any(), anyInt(), any())).thenReturn(1);
		when(groupingRepository.publish(eq(1L), any())).thenReturn(1);

		Assertions.assertThat(publisher.publish(built())).isTrue();

		verify(groupingRepository).supersedeActive(FileType.PHOTO, FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1,
				SimilarityConstants.GROUPING_VERSION, PARAMETERS);
		verify(groupingRepository).publish(1L, NOW);
	}

	@Test
	void aGroupingThatIsNoLongerBuildingIsNotPublished() {
		when(groupingRepository.supersedeActive(any(), any(), anyInt(), any())).thenReturn(0);
		when(groupingRepository.publish(eq(1L), any())).thenReturn(0);

		Assertions.assertThat(publisher.publish(built())).isFalse();
	}

	private SimilarityGrouping built() {
		return SimilarityGrouping.builder().id(1L).publicId(UUID.randomUUID()).mediaType(FileType.PHOTO)
				.algorithmId(FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1)
				.groupingVersion(SimilarityConstants.GROUPING_VERSION).parametersDigest(PARAMETERS)
				.compositionDigest(COMPOSITION).eligibleCount(120).analyzedCount(120).candidateLimit(8000)
				.selectionPolicy(SimilarityConstants.SELECTION_OLDEST_FIRST).status(GroupingStatus.BUILDING)
				.groupCount(1).memberCount(2).build();
	}

	private SimilarityAnalysisResult result(AnalyzedGroup... groups) {
		return new SimilarityAnalysisResult(
				new SimilarityFamily(FileType.PHOTO, FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1,
						SimilarityConstants.GROUPING_VERSION, PARAMETERS),
				new SimilarityComposition(COMPOSITION, 120, 120, 8000, SimilarityConstants.SELECTION_OLDEST_FIRST),
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