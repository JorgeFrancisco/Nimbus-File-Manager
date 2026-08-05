package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.PublishedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.PublishedMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityFamily;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGroupMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGrouping;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityGroupMemberRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityGroupRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityGroupingRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.SimilarityMemberFile;

/**
 * Reading the published analysis: the only way a screen learns about
 * similarity.
 *
 * <p>
 * One page is one page. The database returns twenty groups ordered by the
 * position the analysis froze, their members come back in a second query, and
 * the catalog rows for those members in a third - three round trips whatever the
 * size of the result. Nothing is deserialized whole, nothing is re-clustered,
 * and no comparison happens here: that is what made the old cache necessary and
 * what the durable result exists to end.
 *
 * <p>
 * The reading never writes. A member deleted or quarantined since the analysis
 * is still returned - the analysis was true when it ran, and rewriting it now
 * because the library moved would be reconciliation dressed as a query. What the
 * reading does decide is whether the screen may offer an action over that
 * member, which is a question about now rather than about then.
 */
@Service
@Transactional(readOnly = true)
public class SimilarityResultReader {

	private final SimilarityGroupingRepository groupingRepository;
	private final SimilarityGroupRepository groupRepository;
	private final SimilarityGroupMemberRepository memberRepository;
	private final MediaFingerprintRepository mediaFingerprintRepository;

	public SimilarityResultReader(SimilarityGroupingRepository groupingRepository,
			SimilarityGroupRepository groupRepository, SimilarityGroupMemberRepository memberRepository,
			MediaFingerprintRepository mediaFingerprintRepository) {
		this.groupingRepository = groupingRepository;
		this.groupRepository = groupRepository;
		this.memberRepository = memberRepository;
		this.mediaFingerprintRepository = mediaFingerprintRepository;
	}

	/**
	 * The published answer for a family, or empty when none was ever published.
	 * BUILDING rows cannot be returned: the query names ACTIVE.
	 */
	public Optional<SimilarityGrouping> active(SimilarityFamily family) {
		return groupingRepository.findActive(family.mediaType(), family.algorithmId(), family.groupingVersion(),
				family.parametersDigest());
	}

	/** One page of a published analysis, ordered as the analysis decided. */
	public Page<PublishedGroup> page(SimilarityGrouping grouping, Pageable pageable) {
		Page<SimilarityGroup> groups = groupRepository.findByGroupingIdOrderByPositionAsc(grouping.getId(), pageable);

		if (groups.isEmpty()) {
			// Nothing to read members for, so the two remaining queries are skipped. The
			// total is carried over: a page past the end still has to tell the screen how
			// many groups exist, or the pagination has nothing to render.
			return new PageImpl<>(List.of(), groups.getPageable(), groups.getTotalElements());
		}

		List<SimilarityGroupMember> members = memberRepository.findByGroupIdInOrderByGroupIdAscPositionAsc(
				groups.getContent().stream().map(SimilarityGroup::getId).toList());

		Map<UUID, SimilarityMemberFile> files = filesOf(members);

		Map<Long, List<SimilarityGroupMember>> byGroup = members.stream()
				.collect(Collectors.groupingBy(SimilarityGroupMember::getGroupId));

		return groups.map(group -> toPublished(group, byGroup.getOrDefault(group.getId(), List.of()), files));
	}

	private Map<UUID, SimilarityMemberFile> filesOf(List<SimilarityGroupMember> members) {
		List<UUID> ids = members.stream().map(SimilarityGroupMember::getMediaPublicId).distinct().toList();

		return mediaFingerprintRepository.findSimilarityMembers(ids).stream()
				.collect(Collectors.toMap(SimilarityMemberFile::publicId, Function.identity(), (first, _) -> first));
	}

	private PublishedGroup toPublished(SimilarityGroup group, List<SimilarityGroupMember> members,
			Map<UUID, SimilarityMemberFile> files) {
		List<PublishedMember> published = members.stream().map(member -> toPublished(member, files)).toList();

		int actionable = (int) published.stream().filter(PublishedMember::actionable).count();

		return new PublishedGroup(String.valueOf(group.getId()), group.getSimilarityPercent(), group.getWastedBytes(),
				published, actionable);
	}

	/**
	 * A member whose catalog row vanished comes back with no file and no actions.
	 * It is not dropped: the group said this many files were alike, and silently
	 * showing fewer would misreport what the analysis found.
	 */
	private PublishedMember toPublished(SimilarityGroupMember member, Map<UUID, SimilarityMemberFile> files) {
		SimilarityMemberFile file = files.get(member.getMediaPublicId());

		return new PublishedMember(
				new AnalyzedMember(member.getMediaPublicId(), member.getVerdict(), member.getReason()), file,
				file != null && file.actionable());
	}
}