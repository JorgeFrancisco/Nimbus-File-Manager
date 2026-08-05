package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.FingerprintAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.SimilarityConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateFileResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.GroupParts;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.RelationParameters;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarPhotoGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityComposition;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityFamily;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityRelationRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.CompositionRow;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.MediaQuality;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoHashRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence.SimilarityRelationWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SizeResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import lombok.extern.slf4j.Slf4j;

/**
 * Finds visually related photos in two stages: a 256-bit pHash cheaply rejects
 * unrelated pairs, then SSIM confirms candidates and supplies the percentage
 * shown in the UI. A pHash match is never described as an equality or
 * percentage.
 *
 * <p>
 * The class is an engine, not an entry point: nothing here decides when to run.
 * A screen asks {@link SimilarityViewService} what was published, and a run is a
 * row in the queue that a worker takes - so the expensive grouping happens once
 * per definition and survives a restart, instead of once per process.
 *
 * <p>
 * <b>No transaction of its own, deliberately, and not even a read-only one.</b>
 *
 * <p>
 * It carried {@code @Transactional(readOnly = true)} at class level, and on
 * PostgreSQL that is not a hint: it reaches the connection as
 * {@code SET TRANSACTION READ ONLY}, and the driver then refuses every insert
 * made under it - so the relations a rebuild approved could not be stored at
 * all. What the annotation described and what it did had come apart.
 *
 * <p>
 * Nothing here needs one. Every read is a single repository call, each already
 * transactional on its own, and one transaction around several of them would
 * not even make them agree - PostgreSQL reads a new snapshot per statement at
 * this isolation level. The only write goes through
 * {@link SimilarityRelationWriter}, which opens the short transaction where the
 * relations and the coverage that accounts for them are made atomic. Annotating
 * the methods instead would have been worse than nothing: they call each other,
 * and a call from inside the object never passes through the Spring proxy, so
 * the annotations would have been decoration.
 */
@Slf4j
@Service
class PhotoSimilarityService implements SimilarityAnalyzer, SimilarityRegrouper, SimilarityAdder {

	/**
	 * Generous pHash candidate radius, a wide fraction of the hash. SSIM makes the
	 * final decision, so this stage is intentionally optimized for recall rather
	 * than precision.
	 */
	private static final int MAX_PHASH_CANDIDATE_DISTANCE = 96;

	private final MediaFingerprintRepository mediaFingerprintRepository;
	private final DuplicateGroupAssembler duplicateGroupAssembler;
	private final LuminanceSsimService luminanceSsimService;
	private final DuplicateExclusionService duplicateExclusionService;
	private final SimilarityRelationWriter similarityRelationWriter;
	private final SimilarityRelationRepository similarityRelationRepository;

	PhotoSimilarityService(MediaFingerprintRepository mediaFingerprintRepository,
			DuplicateGroupAssembler duplicateGroupAssembler, LuminanceSsimService luminanceSsimService,
			DuplicateExclusionService duplicateExclusionService, SimilarityRelationWriter similarityRelationWriter,
			SimilarityRelationRepository similarityRelationRepository) {
		this.mediaFingerprintRepository = mediaFingerprintRepository;
		this.duplicateGroupAssembler = duplicateGroupAssembler;
		this.luminanceSsimService = luminanceSsimService;
		this.duplicateExclusionService = duplicateExclusionService;
		this.similarityRelationWriter = similarityRelationWriter;
		this.similarityRelationRepository = similarityRelationRepository;
	}

	@Override
	public FileType mediaType() {
		return FileType.PHOTO;
	}

	@Override
	public SimilarityFamily family(int minSimilarityPercent) {
		return new SimilarityFamily(FileType.PHOTO, FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1,
				SimilarityConstants.GROUPING_VERSION, parametersDigest(SimilarityBounds.clamp(minSimilarityPercent)));
	}

	/**
	 * Every effective parameter of the photo grouping, in a fixed order. The
	 * selection policy is in here because it decides <em>which</em> files are
	 * compared, which changes the answer as surely as the threshold does; the
	 * exclusion signature is here because a file the user hid is a file the
	 * analysis may not see.
	 *
	 * <p>
	 * There is no candidate cap in it any more, and its removal is what makes every
	 * previously published photo analysis stop being read: the digest is the
	 * family's identity, so a result computed over the first 8.000 files lives on
	 * as its own family rather than being served as an answer about the whole
	 * library. That is the honest outcome - those results <em>were</em> about 8.000
	 * files - and it costs one full analysis, once.
	 */
	private String parametersDigest(int minimumSsim) {
		return new SimilarityParameters().with("minSimilarity", minimumSsim)
				.with("maxPhashCandidateDistance", MAX_PHASH_CANDIDATE_DISTANCE)
				.with("selectionPolicy", SimilarityConstants.SELECTION_OLDEST_ELIGIBLE_FIRST)
				.with("exclusions", duplicateExclusionService.signature()).digest();
	}

	/**
	 * The three values a relation is keyed by, which are a subset of the family's
	 * parameters and deliberately not the whole digest: exclusions and selection
	 * policy decide which files enter an analysis, not whether two of them look
	 * alike.
	 */
	private RelationParameters relationParameters(int minimumSsim) {
		return new RelationParameters(FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1,
				MAX_PHASH_CANDIDATE_DISTANCE, minimumSsim);
	}

	@Override
	public int eligibleCount() {
		return mediaFingerprintRepository.countEligibleForSimilarity(FingerprintKind.PHOTO_PHASH.name(),
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1);
	}

	/**
	 * The thresholds this installation has actually analysed photos at.
	 *
	 * <p>
	 * Read from the coverage rather than from a setting, because the question an
	 * arrival raises is not "what would somebody choose" but "which answers exist
	 * and are now out of date". The threshold is per user and per screen, so there
	 * is no single number to consult; what there is, is the record of the runs that
	 * happened - and a family with coverage is exactly a family somebody analysed.
	 *
	 * <p>
	 * Empty until the first analysis, which is the behaviour that matters most: a
	 * library being filled for the first time queues no incremental work, because
	 * there is no answer yet for an arrival to bring up to date.
	 */
	List<Integer> analysedThresholds() {
		return similarityRelationRepository.findAnalysedThresholds(FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1,
				MAX_PHASH_CANDIDATE_DISTANCE, RelationParameters.NO_MEDIA_PARAMETERS);
	}

	@Override
	public SimilarityComposition composition() {
		return compositionOf(SimilarityGroupSupport.canonicalComposition(
				mediaFingerprintRepository.findPhotoCompositionRows(FingerprintKind.PHOTO_PHASH,
						FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, eligibleIds()),
				CompositionRow::mediaPublicId, CompositionRow::currentFolder, duplicateExclusionService));
	}

	/**
	 * Every eligible photo, in catalog order, with nothing cut off the end.
	 *
	 * <p>
	 * There used to be a cap of 8.000 here, and what it did in the end was
	 * truncate: a library of 119.830 photos was analysed 8.000 at a time, always
	 * the same 8.000, and 93% of it was never compared with anything. It was
	 * introduced as a memory guard and the measurements retired that reason - the
	 * whole library's hashes are 3,7 MB, the samples are read only for the pairs
	 * that survive the distance filter, and the production path over all of it
	 * peaked at 469 MB against the worker's 4 GB while taking 33 s.
	 *
	 * <p>
	 * The query takes no limit at all any more - neither medium caps, so there is
	 * nothing to pass and nothing that could be passed by mistake.
	 */
	private List<Long> eligibleIds() {
		return mediaFingerprintRepository.findEligibleForSimilarity(FingerprintKind.PHOTO_PHASH.name(),
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1);
	}

	private SimilarityComposition compositionOf(List<CompositionRow> rows) {
		return new SimilarityComposition(
				SimilarityDigest.ofComposition(rows.stream().map(CompositionRow::mediaPublicId).toList(),
						rows.stream().map(CompositionRow::currentFolder).toList()),
				eligibleCount(), rows.size(), SimilarityConstants.NO_CANDIDATE_LIMIT,
				SimilarityConstants.SELECTION_OLDEST_ELIGIBLE_FIRST);
	}

	/**
	 * The analysis, and the account of what it was about.
	 *
	 * <p>
	 * The exclusions are applied by the query now, before the cap, so the run works
	 * on as many files as the cap promises. The in-memory pass over the rows stays:
	 * it is what derives the canonical composition, and it is a no-op for exclusion
	 * unless the user hid something between the queueing and the run - in which
	 * case honouring it late is better than ignoring it.
	 *
	 * <p>
	 * It runs in no transaction, for the reason given on the class, and the writer
	 * opens its own for the write - a short one, and the one place where the
	 * relations and the coverage that accounts for them are made atomic. Wrapping
	 * the analysis in one would hold it open for minutes and buy nothing.
	 */
	@Override
	public SimilarityAnalysisResult analyze(int minSimilarityPercent, SimilarityProgressCallback progress) {
		int minimumSsim = SimilarityBounds.clamp(minSimilarityPercent);

		List<PhotoHashRawResponse> rows = mediaFingerprintRepository.findFingerprintedPhotos(
				FingerprintKind.PHOTO_PHASH, FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, eligibleIds());

		// The selection happens once, in the primitive the application also calls, and
		// the heavy rows are filtered by what it chose - rather than being selected a
		// second time by an equivalent-looking filter.
		List<CompositionRow> selected = SimilarityGroupSupport.canonicalComposition(rows, PhotoHashRawResponse::id,
				PhotoHashRawResponse::currentFolder, duplicateExclusionService);

		Set<UUID> analysed = selected.stream().map(CompositionRow::mediaPublicId).collect(Collectors.toSet());

		List<PhotoHashRawResponse> candidates = rows.stream().filter(row -> analysed.contains(row.id())).toList();

		List<SimilarPhotoGroupResponse> responses = group(candidates, minimumSsim, progress);

		SimilarityComposition composition = compositionOf(selected);

		return new SimilarityAnalysisResult(family(minimumSsim), composition, toAnalyzedGroups(responses));
	}

	/**
	 * The same answer, from the relations already approved - nothing compared.
	 *
	 * <p>
	 * What a removal leaves behind is a set of relations that is still entirely
	 * true. Whether two photos look alike does not depend on which other photos
	 * exist, so the ones naming a file that left are simply not read and the rest
	 * need no revisiting. That is the whole saving: the measured rebuild spends its
	 * time on the distance scan and on SSIM, and this does neither.
	 *
	 * <p>
	 * The grouping itself <em>is</em> run again, over everything, and that is not a
	 * shortcut being missed. The placement is greedy: a candidate joins the first
	 * cluster whose every member it relates to, so a file can be the reason another
	 * was refused, and its departure has to let that refusal be taken again.
	 * Editing the published groups - dropping the member and keeping the rest -
	 * would keep the refusal forever, which is the counterexample
	 * {@code SimilarityIncrementalEquivalenceTest} holds.
	 *
	 * <p>
	 * Only the files that take part in a relation are loaded. A file with no
	 * approved neighbour can only form a group of one, which is not a result, and
	 * it can never be the reason another candidate was refused - so leaving it out
	 * produces the same groups over a fraction of the rows.
	 */
	@Override
	public SimilarityAnalysisResult regroup(int minSimilarityPercent, SimilarityProgressCallback progress) {
		int minimumSsim = SimilarityBounds.clamp(minSimilarityPercent);

		return regroupOver(eligibleIds(), minimumSsim, progress);
	}

	/**
	 * Everything that arrived since the last run, incorporated - and then the same
	 * grouping a removal gets.
	 *
	 * <p>
	 * The two halves are deliberately unequal. The first compares, and it is the
	 * only part that can discover anything: the files nobody has incorporated yet
	 * against every file that was, and against each other. The second compares
	 * nothing and simply reads back what is stored for the eligible set, which is
	 * exactly what {@link #regroup} does - so an arrival and a removal reach their
	 * answer through the same code, and the answer is the one a rebuild would give
	 * over the same final set.
	 *
	 * <p>
	 * Reading the relations back from the database rather than grouping the
	 * structure just built is not a wasted query. What was just built covers the
	 * arrivals only; the answer is about every eligible file, and the relations of
	 * the ones that did not arrive are in the table and nowhere else.
	 *
	 * <p>
	 * The order of the two halves is the transactional rule this rests on:
	 * relations and coverage are written together, and coverage is never granted
	 * for a file whose relations are not stored. A crash between them leaves the
	 * file new, which costs a repeat; the other way round would leave a pair
	 * nobody ever evaluates.
	 *
	 * <p>
	 * A run that finds nothing new still regroups and still publishes. It is the
	 * ordinary outcome of asking twice, and the answer it produces is correct -
	 * refusing to publish would leave whoever asked with no result and no reason.
	 */
	@Override
	public SimilarityAnalysisResult add(int minSimilarityPercent, SimilarityProgressCallback progress) {
		int minimumSsim = SimilarityBounds.clamp(minSimilarityPercent);

		List<Long> eligible = eligibleIds();

		RelationParameters parameters = relationParameters(minimumSsim);

		List<Long> newcomers = similarityRelationRepository.findEligibleNotCovered(parameters.algorithmId(),
				parameters.maxDistance(), parameters.minSimilarity(), parameters.relationDigest(),
				eligible.toArray(Long[]::new));

		if (newcomers.isEmpty()) {
			return regroupOver(eligible, minimumSsim, progress);
		}

		List<Long> covered = similarityRelationRepository.findCovered(parameters.algorithmId(),
				parameters.maxDistance(), parameters.minSimilarity(), parameters.relationDigest());

		if (rebuildIsCheaper(newcomers.size(), covered.size())) {
			log.info("{} photo(s) are not yet incorporated against {} that are, so a full rebuild costs less than"
					+ " incorporating them one against the other; running the rebuild", newcomers.size(),
					covered.size());

			return analyze(minimumSsim, progress);
		}

		incorporate(parameters, newcomers, covered, minimumSsim, progress);

		return regroupOver(eligible, minimumSsim, progress);
	}

	/**
	 * Which of the two equivalent routes is the cheap one, decided by counting
	 * rather than by a number somebody chose.
	 *
	 * <p>
	 * The two produce the same answer - that is what the equivalence tests hold -
	 * so the only question is cost, and the cost of both is dominated by the
	 * distance scan. An arrival's scan walks the whole library once per newcomer,
	 * which is {@code N x T} iterations for a library of {@code T = N + C}; a
	 * rebuild walks every pair once, {@code T x (T - 1) / 2}. The arrival is
	 * cheaper exactly while {@code N x T < T² / 2}, that is while
	 * {@code N < T / 2} - and since {@code T = N + C}, that is simply
	 * <b>while the newcomers are fewer than the files already covered</b>.
	 *
	 * <p>
	 * The tie goes to the arrival. At {@code N = C} the two are within a rounding
	 * of each other, and the arrival has a second advantage the count does not
	 * show: it adds to the stored relations, where a rebuild deletes the family's
	 * set and writes it again.
	 *
	 * <p>
	 * Note that this is about <em>iterations</em>, not about pairs. The pairs an
	 * arrival evaluates are {@code N x C + N x (N - 1) / 2}, which is never more
	 * than a rebuild's; but the scan still visits the files it skips, and the skip
	 * is what the comparison above prices. The measured rate on a real library
	 * agrees: 684 ms for 1.000 arrivals against 23,70 s for the rebuild's scan of
	 * the same 119.830 photos.
	 *
	 * <p>
	 * The case this exists for is the upgrade that removed the candidate cap:
	 * 8.000 photos were covered and 111.830 suddenly were not. Letting that fall
	 * into the incremental path would have cost nearly twice a rebuild to reach
	 * the answer a rebuild reaches directly.
	 */
	private boolean rebuildIsCheaper(int newcomers, int covered) {
		return newcomers > covered;
	}

	/**
	 * The comparing half of an arrival: the pairs the newcomers create, and the
	 * record that they are now part of the universe.
	 */
	private void incorporate(RelationParameters parameters, List<Long> newcomers, List<Long> covered, int minimumSsim,
			SimilarityProgressCallback progress) {
		ArrivingRelations arriving = new PhotoArrivalRelationBuilder(mediaFingerprintRepository, luminanceSsimService,
				MAX_PHASH_CANDIDATE_DISTANCE).build(parameters, newcomers, covered, minimumSsim, progress);

		BuiltRelations built = arriving.relations();

		similarityRelationWriter.save(parameters, built.first(), built.second(), built.scores(), built.count(),
				arriving.catalogFileIds(), arriving.newlyCovered());

		log.info("Incorporated {} arriving photo(s) against {} already covered: {} pair(s) within the radius,"
				+ " {} sample(s) read, {} relation(s) approved", arriving.newlyCovered().length, covered.size(),
				arriving.candidatePairs(), arriving.samplesLoaded(), built.count());
	}

	/**
	 * The grouping over what is stored, which is the second half of an arrival and
	 * the whole of a removal.
	 *
	 * @param eligible the files the answer is to be about, ascending - passed in
	 * rather than read again so that the relations, the composition and the
	 * arrivals are all about one set
	 */
	private SimilarityAnalysisResult regroupOver(List<Long> eligible, int minimumSsim,
			SimilarityProgressCallback progress) {
		RelationParameters parameters = relationParameters(minimumSsim);

		StoredRelations stored = StoredRelations.of(similarityRelationRepository.findEligibleRelations(
				parameters.algorithmId(), parameters.maxDistance(), parameters.minSimilarity(),
				parameters.relationDigest(), eligible.toArray(Long[]::new)));

		List<PhotoHashRawResponse> related = mediaFingerprintRepository.findFingerprintedPhotos(
				FingerprintKind.PHOTO_PHASH, FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, stored.participants());

		// The same late look at the exclusions the rebuild takes, for the same reason:
		// the query applied them when it chose the eligible ids, and this only differs
		// if the user hid something in between.
		List<PhotoHashRawResponse> candidates = SimilarityGroupSupport.withoutExcluded(related,
				duplicateExclusionService, PhotoHashRawResponse::id, PhotoHashRawResponse::currentFolder);

		List<SimilarPhotoGroupResponse> responses = group(candidates, stored, progress);

		List<CompositionRow> selected = SimilarityGroupSupport.canonicalComposition(
				mediaFingerprintRepository.findPhotoCompositionRows(FingerprintKind.PHOTO_PHASH,
						FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, eligible),
				CompositionRow::mediaPublicId, CompositionRow::currentFolder, duplicateExclusionService);

		return new SimilarityAnalysisResult(family(minimumSsim), compositionOf(selected),
				toAnalyzedGroups(responses));
	}

	/**
	 * Comparison first, grouping second - and each pair compared once.
	 *
	 * <p>
	 * The two steps used to be one: the grouping called the scorer from inside its
	 * own loop, and because every candidate is offered to every cluster it asked
	 * about most pairs many times over. On the whole library that was 6,37 billion
	 * calls and 351 seconds, against roughly 24 for the same distances and scores
	 * computed once. Splitting them changes no verdict - the relations are the same
	 * relations, and {@code SimilarityRelationGrouperTest} holds the two groupings
	 * to identical output - it only stops the work being repeated.
	 *
	 * <p>
	 * Progress is reported by the comparison, which is where the time is; the
	 * grouping that follows is milliseconds and reporting it would send the bar
	 * back to zero for no reason. Cancellation is still checked per candidate, in
	 * the phase long enough for anyone to want to cancel it.
	 */
	private List<SimilarPhotoGroupResponse> group(List<PhotoHashRawResponse> candidates, int minimumSsim,
			SimilarityProgressCallback progress) {
		List<UUID> allIds = candidates.stream().map(PhotoHashRawResponse::id).toList();

		Map<UUID, MediaQuality> quality = duplicateGroupAssembler.qualityByPublicId(allIds);

		BuiltRelations built = new PhotoRelationBuilder(luminanceSsimService, MAX_PHASH_CANDIDATE_DISTANCE)
				.build(candidates, minimumSsim, progress);

		persist(built, candidates, minimumSsim);

		ApprovedRelations relations = built.relations();

		List<List<Integer>> clusters = SimilarityRelationGrouper.cluster(candidates.size(), relations, (_, _) -> {
		});

		return toResponses(clusters, candidates, relations, quality);
	}

	/**
	 * The grouping alone, over relations that were approved by an earlier run.
	 *
	 * <p>
	 * The candidates decide the positions and the relations are indexed against
	 * them, rather than the other way round: the rows arrive in
	 * {@code catalog_file.id} order, which is the order the greedy placement
	 * depends on, and a relation naming a file the load did not return is dropped
	 * instead of shifting every position after it.
	 *
	 * <p>
	 * Progress is reported at the ends and not per candidate. The callback writes
	 * to the database and the clustering of a whole library is milliseconds, so a
	 * report per candidate would cost more than the work it describes; the long
	 * part of a regroup is reading the relations, which is one query and has no
	 * inside to report from.
	 */
	private List<SimilarPhotoGroupResponse> group(List<PhotoHashRawResponse> candidates, StoredRelations stored,
			SimilarityProgressCallback progress) {
		progress.update(0, candidates.size());

		long[] nodes = new long[candidates.size()];

		for (int index = 0; index < candidates.size(); index++) {
			nodes[index] = candidates.get(index).catalogFileId();
		}

		Map<UUID, MediaQuality> quality = duplicateGroupAssembler
				.qualityByPublicId(candidates.stream().map(PhotoHashRawResponse::id).toList());

		ApprovedRelations relations = stored.indexedBy(nodes);

		List<List<Integer>> clusters = SimilarityRelationGrouper.cluster(candidates.size(), relations, (_, _) -> {
		});

		progress.update(candidates.size(), candidates.size());

		return toResponses(clusters, candidates, relations, quality);
	}

	private List<SimilarPhotoGroupResponse> toResponses(List<List<Integer>> clusters,
			List<PhotoHashRawResponse> candidates, ApprovedRelations relations, Map<UUID, MediaQuality> quality) {
		return clusters.stream().map(group -> toResponse(group, candidates, relations, quality))
				.sorted((first, second) -> Long.compare(second.wastedSize().bytes(), first.wastedSize().bytes()))
				.toList();
	}

	private List<AnalyzedGroup> toAnalyzedGroups(List<SimilarPhotoGroupResponse> responses) {
		return responses.stream()
				.map(response -> SimilarityGroupSupport.toAnalyzedGroup(response.similarityPercent(),
						response.wastedSize().bytes(), response.keep(), response.deleteCandidates(),
						response.reviewCandidates()))
				.toList();
	}

	/**
	 * Writes down what this run approved, so the next change to the library does
	 * not have to rediscover it.
	 *
	 * <p>
	 * Replaced rather than merged, and that is a correctness point: only approvals
	 * are stored, so a pair that stops qualifying is simply absent from the new
	 * batch and nothing would overwrite the old row. It would go on being read as
	 * an approval it no longer is - a photo edited in place keeps its catalog id
	 * and drops from 97 to 60 against a neighbour it would then stay grouped with.
	 * A rebuild recomputes the whole set for these parameters, so the whole set is
	 * what it may replace.
	 *
	 * <p>
	 * Keyed by the three parameters that decide a relation, not by the grouping's
	 * digest: exclusions and selection policy decide which files enter an analysis,
	 * not whether two of them look alike, and a folder exclusion must not discard
	 * facts that did not change.
	 */
	private void persist(BuiltRelations built, List<PhotoHashRawResponse> candidates, int minimumSsim) {
		long[] catalogFileIds = new long[candidates.size()];

		for (int index = 0; index < candidates.size(); index++) {
			catalogFileIds[index] = candidates.get(index).catalogFileId();
		}

		similarityRelationWriter.replaceAll(relationParameters(minimumSsim), built.first(), built.second(),
				built.scores(), built.count(), catalogFileIds);
	}

	/**
	 * The group's floor comes from the relations rather than from recomputing:
	 * every pair inside a complete-linkage group was approved to get there, so all
	 * of them are in the structure and none has to be scored again.
	 */
	private SimilarPhotoGroupResponse toResponse(List<Integer> group, List<PhotoHashRawResponse> candidates,
			ApprovedRelations relations, Map<UUID, MediaQuality> quality) {
		List<DuplicateFileResponse> files = group.stream().map(candidates::get).map(this::toFileResponse).toList();

		GroupParts parts = duplicateGroupAssembler.assemble(files, quality, false);

		return new SimilarPhotoGroupResponse(String.valueOf(parts.keep().id()), group.size(),
				SimilarityCompleteLinkageGrouper.worstScore(group, relations::scoreOf),
				SizeResponse.of(parts.wastedBytes()), parts.keep(), parts.deleteCandidates(), parts.reviewCandidates());
	}

	private DuplicateFileResponse toFileResponse(PhotoHashRawResponse raw) {
		return new DuplicateFileResponse(raw.id(), raw.fileName(), raw.extension(), "PHOTO",
				SizeResponse.of(raw.sizeBytes()), raw.currentPath(), raw.currentFolder(), raw.modifiedAt());
	}

}