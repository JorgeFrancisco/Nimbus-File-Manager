package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.PublishedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.SimilarityConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityFamily;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityView;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGrouping;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * What the Duplicados screen and the API are told about similarity.
 *
 * <p>
 * One place decides the state, so the screen and the REST contract can never
 * disagree about whether an answer is current. Neither of them computes
 * anything: this reads the published result, asks whether the library has moved
 * since, and asks whether an analysis is already on its way.
 */
@Service
public class SimilarityViewService {

	/** Queued or running - either way, an answer is coming and none is late. */
	private static final List<ExecutionStatus> IN_FLIGHT = List.of(ExecutionStatus.PENDING, ExecutionStatus.RUNNING);

	private final PhotoSimilarityService photoSimilarityService;
	private final VideoSimilarityService videoSimilarityService;
	private final SimilarityResultReader reader;
	private final ExecutionRepository executionRepository;

	public SimilarityViewService(PhotoSimilarityService photoSimilarityService,
			VideoSimilarityService videoSimilarityService, SimilarityResultReader reader,
			ExecutionRepository executionRepository) {
		this.photoSimilarityService = photoSimilarityService;
		this.videoSimilarityService = videoSimilarityService;
		this.reader = reader;
		this.executionRepository = executionRepository;
	}

	public SimilarityView photos(int minSimilarityPercent, Pageable pageable) {
		return view(photoSimilarityService, ExecutionType.SIMILARITY_PHOTO, minSimilarityPercent, pageable);
	}

	public SimilarityView videos(int minSimilarityPercent, Pageable pageable) {
		return view(videoSimilarityService, ExecutionType.SIMILARITY_VIDEO, minSimilarityPercent, pageable);
	}

	/**
	 * The published answer, if any, plus what is true about it now.
	 *
	 * <p>
	 * Staleness is decided by comparing the composition the analysis recorded with
	 * the one an analysis started now would have. That comparison is honest about
	 * its own limits: it proves the analysed set is no longer the set that would be
	 * analysed, and deliberately claims nothing about <em>how</em> it differs -
	 * arrivals, deletions, restores from quarantine, moves and new exclusions all
	 * look the same from here, and a screen saying "20 new photos" when the truth
	 * was "one photo was quarantined" would be worse than saying less.
	 */
	private SimilarityView view(SimilarityAnalyzer analyzer, ExecutionType type, int minSimilarityPercent,
			Pageable pageable) {
		SimilarityFamily family = analyzer.family(SimilarityBounds.clamp(minSimilarityPercent));

		Optional<SimilarityGrouping> active = reader.active(family);

		boolean analyzing = executionRepository.existsByExecutionTypeAndStatusIn(type, IN_FLIGHT);

		if (active.isEmpty()) {
			return new SimilarityView(Page.empty(pageable), false, false, analyzing, analyzer.eligibleCount(), 0,
					SimilarityConstants.NO_CANDIDATE_LIMIT, false);
		}

		SimilarityGrouping grouping = active.get();

		Page<PublishedGroup> groups = reader.page(grouping, pageable);

		return new SimilarityView(groups, true, outdated(analyzer, grouping), analyzing, grouping.getEligibleCount(),
				grouping.getAnalyzedCount(), grouping.getCandidateLimit(), grouping.coverageComplete());
	}

	/**
	 * Whether the world moved since the analysis. Reading it costs the light
	 * projection of the candidates - ids and folders - which is what identifying a
	 * composition costs; the analysis itself is untouched either way.
	 */
	private boolean outdated(SimilarityAnalyzer analyzer, SimilarityGrouping grouping) {
		return !analyzer.composition().digest().equals(grouping.getCompositionDigest());
	}

	/** Which medium a family belongs to, for callers that only have the type. */
	public FileType mediaTypeOf(ExecutionType type) {
		return type == ExecutionType.SIMILARITY_VIDEO ? FileType.VIDEO : FileType.PHOTO;
	}
}