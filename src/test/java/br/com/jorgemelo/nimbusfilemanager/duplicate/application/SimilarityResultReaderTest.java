package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.FingerprintAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.SimilarityConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.PublishedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityFamily;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.GroupingStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Reason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Verdict;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGroupMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGrouping;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityGroupMemberRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityGroupRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityGroupingRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.SimilarityMemberFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;

/**
 * The reader turns rows that were published once into what a screen shows now.
 * What is worth asserting is exactly the seam between the two: the decision is
 * frozen, the file is read live, and a file that has since left the catalog or
 * the ACTIVE lifecycle must come back marked as not actionable rather than
 * silently disappearing.
 */
class SimilarityResultReaderTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.MAY, 1, 10, 0);
	/** When the file was last written to, which is a moment on the timeline. */
	private static final Instant WRITTEN_AT = Instant.parse("2026-05-01T10:00:00Z");

	private final SimilarityGroupingRepository groupingRepository = mock(SimilarityGroupingRepository.class);
	private final SimilarityGroupRepository groupRepository = mock(SimilarityGroupRepository.class);
	private final SimilarityGroupMemberRepository memberRepository = mock(SimilarityGroupMemberRepository.class);
	private final MediaFingerprintRepository mediaFingerprintRepository = mock(MediaFingerprintRepository.class);

	private final SimilarityResultReader reader = new SimilarityResultReader(groupingRepository, groupRepository,
			memberRepository, mediaFingerprintRepository);

	@Test
	void readsThePublishedDecisionAndTheCurrentFileOfEachMember() {
		UUID keep = UUID.randomUUID();
		UUID copy = UUID.randomUUID();

		when(groupRepository.findByGroupingIdOrderByPositionAsc(any(), any()))
				.thenReturn(page(group(7L, 96, 2048L)));
		when(memberRepository.findByGroupIdInOrderByGroupIdAscPositionAsc(List.of(7L)))
				.thenReturn(List.of(member(7L, keep, Verdict.KEEP, Reason.ORIGINAL, 0),
						member(7L, copy, Verdict.DELETE_CANDIDATE, Reason.IDENTICAL_COPY, 1)));
		when(mediaFingerprintRepository.findSimilarityMembers(any()))
				.thenReturn(List.of(file(keep, "original.jpg", LifecycleStatus.ACTIVE),
						file(copy, "copy.jpg", LifecycleStatus.ACTIVE)));

		PublishedGroup published = reader.page(grouping(), PageRequest.of(0, 20)).getContent().getFirst();

		Assertions.assertThat(published.groupId()).isEqualTo("7");
		Assertions.assertThat(published.similarityPercent()).isEqualTo(96);
		Assertions.assertThat(published.wastedBytes()).isEqualTo(2048L);
		Assertions.assertThat(published.actionableMembers()).isEqualTo(2);

		Assertions.assertThat(published.members()).extracting(member -> member.decision().mediaPublicId())
				.containsExactly(keep, copy);
		Assertions.assertThat(published.members()).extracting(member -> member.file().fileName())
				.containsExactly("original.jpg", "copy.jpg");
	}

	@Test
	void aMemberMissingSincePublicationComesBackAsNotActionable() {
		UUID keep = UUID.randomUUID();
		UUID missing = UUID.randomUUID();

		when(groupRepository.findByGroupingIdOrderByPositionAsc(any(), any()))
				.thenReturn(page(group(7L, 96, 2048L)));
		when(memberRepository.findByGroupIdInOrderByGroupIdAscPositionAsc(List.of(7L)))
				.thenReturn(List.of(member(7L, keep, Verdict.KEEP, Reason.ORIGINAL, 0),
						member(7L, missing, Verdict.DELETE_CANDIDATE, Reason.IDENTICAL_COPY, 1)));
		when(mediaFingerprintRepository.findSimilarityMembers(any()))
				.thenReturn(List.of(file(keep, "original.jpg", LifecycleStatus.ACTIVE),
						file(missing, "copy.jpg", LifecycleStatus.MISSING)));

		PublishedGroup published = reader.page(grouping(), PageRequest.of(0, 20)).getContent().getFirst();

		Assertions.assertThat(published.members()).hasSize(2);
		Assertions.assertThat(published.actionableMembers()).isEqualTo(1);
		Assertions.assertThat(published.members().get(1).actionable()).isFalse();
	}

	@Test
	void aMemberWhoseCatalogRecordIsGoneKeepsItsPlaceWithoutAFile() {
		UUID keep = UUID.randomUUID();
		UUID vanished = UUID.randomUUID();

		when(groupRepository.findByGroupingIdOrderByPositionAsc(any(), any()))
				.thenReturn(page(group(7L, 96, 2048L)));
		when(memberRepository.findByGroupIdInOrderByGroupIdAscPositionAsc(List.of(7L)))
				.thenReturn(List.of(member(7L, keep, Verdict.KEEP, Reason.ORIGINAL, 0),
						member(7L, vanished, Verdict.DELETE_CANDIDATE, Reason.IDENTICAL_COPY, 1)));
		when(mediaFingerprintRepository.findSimilarityMembers(any()))
				.thenReturn(List.of(file(keep, "original.jpg", LifecycleStatus.ACTIVE)));

		PublishedGroup published = reader.page(grouping(), PageRequest.of(0, 20)).getContent().getFirst();

		Assertions.assertThat(published.members()).hasSize(2);
		Assertions.assertThat(published.members().get(1).file()).isNull();
		Assertions.assertThat(published.members().get(1).actionable()).isFalse();
		Assertions.assertThat(published.actionableMembers()).isEqualTo(1);
	}

	@Test
	void aPageBeyondTheLastGroupCostsNoMemberQuery() {
		when(groupRepository.findByGroupingIdOrderByPositionAsc(any(), any()))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(9, 20), 3));

		Page<PublishedGroup> page = reader.page(grouping(), PageRequest.of(9, 20));

		Assertions.assertThat(page).isEmpty();
		Assertions.assertThat(page.getTotalElements()).isEqualTo(3);

		Mockito.verifyNoInteractions(memberRepository);
		Mockito.verifyNoInteractions(mediaFingerprintRepository);
	}

	@Test
	void theActiveAnswerIsLookedUpByFamilyAndNothingElse() {
		SimilarityFamily family = new SimilarityFamily(FileType.PHOTO,
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, SimilarityConstants.GROUPING_VERSION, "p".repeat(64));

		when(groupingRepository.findActive(any(), any(), anyInt(), any())).thenReturn(Optional.of(grouping()));

		Assertions.assertThat(reader.active(family)).isPresent();

		verify(groupingRepository).findActive(FileType.PHOTO, FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1,
				SimilarityConstants.GROUPING_VERSION, "p".repeat(64));
	}

	private Page<SimilarityGroup> page(SimilarityGroup group) {
		return new PageImpl<>(List.of(group), Pageable.ofSize(20), 1);
	}

	private SimilarityGrouping grouping() {
		return SimilarityGrouping.builder().id(1L).similarityGroupingPublicId(UUID.randomUUID()).mediaType(FileType.PHOTO)
				.status(GroupingStatus.ACTIVE).build();
	}

	private SimilarityGroup group(Long id, int similarityPercent, long wastedBytes) {
		return SimilarityGroup.builder().id(id).groupingId(1L).similarityPercent(similarityPercent).fileCount(2)
				.wastedBytes(wastedBytes).position(0).build();
	}

	private SimilarityGroupMember member(Long groupId, UUID mediaPublicId, Verdict verdict, Reason reason,
			int position) {
		return SimilarityGroupMember.builder().groupId(groupId).catalogFilePublicId(mediaPublicId).verdict(verdict)
				.reason(reason).position(position).build();
	}

	private SimilarityMemberFile file(UUID publicId, String name, LifecycleStatus status) {
		return new SimilarityMemberFile(publicId, name, "jpg", "PHOTO", 1024L, "C:/fotos/" + name, "C:/fotos",
				WRITTEN_AT,
				1920, 1080, NOW, DateSource.EXIF, status);
	}
}