package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static br.com.jorgemelo.nimbusfilemanager.duplicate.application.SyntheticVideoSignatures.DEFAULT_DURATION_SECONDS;
import static br.com.jorgemelo.nimbusfilemanager.duplicate.application.SyntheticVideoSignatures.DEFAULT_HEIGHT;
import static br.com.jorgemelo.nimbusfilemanager.duplicate.application.SyntheticVideoSignatures.DEFAULT_WIDTH;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.FingerprintAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoFrameHash;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoSignature;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaQualityRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.CompositionRow;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.VideoFrameRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.VideoFrameRow;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.VideoGateRow;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence.SimilarityRelationWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.VideoSimilarityProperties;

/**
 * A library of videos and the tables the analysis reads and writes, kept in
 * memory and behaving the way the database behaves.
 *
 * <p>
 * The sibling of {@link PhotoSimilarityLibrary}, and deliberately not a copy of it:
 * the two relation tables are the same tables with the same semantics, so both
 * harnesses drive the same {@link RelationStore}. What is here is only what a
 * video query answers differently - frames per video rather than one hash and
 * one sample, and the duration and display size the cheap gates read.
 *
 * <p>
 * It exists so that a rebuild and a sequence of arrivals run through the
 * <em>production</em> code and their answers are compared. Asserting an arrival
 * against a hand-written expectation would only check that the expectation and
 * the code were written by the same person on the same afternoon; asserting it
 * against the rebuild checks the claim the whole design rests on.
 *
 * <p>
 * Two behaviours are worth naming because they are the ones the arrival turns
 * on. The gate query returns every fingerprinted video, eligible or not, because
 * a covered file hidden today is still part of the relation universe; and the
 * frame query does the same, because an arrival compares against the covered set
 * rather than against the eligible one.
 */
final class VideoSimilarityLibrary {

	/** Every video's frame rows, ordered by sample index, keyed by catalog id. */
	private final Map<Long, List<VideoFrameRawResponse>> videos = new TreeMap<>();

	private final Set<Long> eligible = new TreeSet<>();

	private final RelationStore store = new RelationStore();

	private final MediaFingerprintRepository fingerprints = mock(MediaFingerprintRepository.class);
	private final MediaQualityRepository mediaQualityRepository = mock(MediaQualityRepository.class);
	private final DuplicateExclusionService exclusions = mock(DuplicateExclusionService.class);

	private final VideoSimilarityService service;

	VideoSimilarityLibrary() {
		this(new VideoSimilarityProperties(null, null, null, null, null));
	}

	VideoSimilarityLibrary(VideoSimilarityProperties properties) {
		wireReads();

		when(exclusions.signature()).thenReturn("none");
		when(mediaQualityRepository.findByPublicIdIn(any())).thenReturn(List.of());

		service = new VideoSimilarityService(fingerprints,
				new DuplicateGroupAssembler(new DuplicateKeepPolicy(), mediaQualityRepository),
				new FfmpegLanczosFramesPhashAlgorithm(null, new LuminanceSsimService(), properties), exclusions, properties,
				store.writer(), store.repository());
	}

	VideoSimilarityService service() {
		return service;
	}

	SimilarityRelationWriter writer() {
		return store.writer();
	}

	/** A video of the default duration and shape, eligible from the moment it exists. */
	void video(long catalogFileId, int... frames) {
		video(catalogFileId, DEFAULT_DURATION_SECONDS, DEFAULT_WIDTH, DEFAULT_HEIGHT, frames);
	}

	void video(long catalogFileId, Double durationSeconds, Integer width, Integer height, int... frames) {
		VideoSignature signature = SyntheticVideoSignatures.video(catalogFileId, durationSeconds, width, height,
				frames);

		List<VideoFrameRawResponse> rows = new ArrayList<>(signature.frames().size());

		for (VideoFrameHash frame : signature.frames()) {
			rows.add(new VideoFrameRawResponse(catalogFileId, signature.id(), frame.sampleIndex(),
					frame.sampleIndex() * 1000L, frame.phash(), frame.luminance(), catalogFileId + ".mp4", "mp4", 100L,
					"C:/Videos/" + catalogFileId + ".mp4", "C:/Videos",
					LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0), durationSeconds, width, height));
		}

		videos.put(catalogFileId, rows);

		eligible.add(catalogFileId);
	}

	/** The same video with only some of its sample indexes fingerprinted. */
	void videoWithFrames(long catalogFileId, int[] sampleIndexes, int... frames) {
		video(catalogFileId, frames);

		List<VideoFrameRawResponse> kept = new ArrayList<>(sampleIndexes.length);

		for (int sampleIndex : sampleIndexes) {
			kept.add(videos.get(catalogFileId).get(sampleIndex));
		}

		videos.put(catalogFileId, kept);
	}

	/**
	 * The file stops being eligible without stopping being a file - what an
	 * exclusion, a quarantine or a logical deletion does. Its fingerprint stays,
	 * and so does its coverage.
	 */
	void hide(long catalogFileId) {
		eligible.remove(catalogFileId);
	}

	void show(long catalogFileId) {
		eligible.add(catalogFileId);
	}

	/** The file is gone for good: the catalog row and all that cascades from it. */
	void purge(long catalogFileId) {
		videos.remove(catalogFileId);

		eligible.remove(catalogFileId);

		store.purge(catalogFileId);
	}

	/**
	 * The video is fingerprinted again: same catalog id, different frames. The
	 * production path forgets what was computed from the old ones, which is what
	 * puts it back among the files nobody has compared.
	 */
	void refingerprint(long catalogFileId, int... frames) {
		video(catalogFileId, frames);

		store.writer().forget(FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_FRAMES_V1, catalogFileId);
	}

	/**
	 * Its duration or display size was read again and came back different - what a
	 * re-scan or a metadata rebuild does. The production invalidator decides
	 * whether that matters, so the harness asks it rather than deciding itself.
	 */
	void remeasure(long catalogFileId, Double durationSeconds, Integer width, Integer height) {
		List<VideoFrameRawResponse> rows = videos.get(catalogFileId);

		List<VideoFrameRawResponse> updated = new ArrayList<>(rows.size());

		for (VideoFrameRawResponse row : rows) {
			updated.add(new VideoFrameRawResponse(row.catalogFileId(), row.id(), row.sampleIndex(), row.positionMs(),
					row.phash(), row.luminance(), row.fileName(), row.extension(), row.sizeBytes(), row.currentPath(),
					row.currentFolder(), row.modifiedAt(), durationSeconds, width, height));
		}

		videos.put(catalogFileId, updated);

		store.writer().forget(FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_FRAMES_V1, catalogFileId);
	}

	/**
	 * The same videos and the same eligibility, with nothing computed yet - what a
	 * full rebuild is run over, so an arrival's answer can be compared against the
	 * answer to the same question.
	 */
	VideoSimilarityLibrary copy() {
		VideoSimilarityLibrary copy = new VideoSimilarityLibrary();

		copy.videos.putAll(videos);
		copy.eligible.addAll(eligible);

		return copy;
	}

	int relationCount() {
		return store.relationCount();
	}

	Set<Long> covered() {
		return store.covered();
	}

	Set<String> approvedPairs() {
		return store.approvedPairs();
	}

	List<Integer> scoresOf(long left, long right) {
		return store.scoresOf(left, right);
	}

	private void wireReads() {
		when(fingerprints.findEligibleForSimilarity(any(), any())).thenAnswer(_ -> eligible.stream().toList());

		when(fingerprints.countEligibleForSimilarity(any(), any())).thenAnswer(_ -> eligible.size());

		when(fingerprints.findFingerprintedVideoFrames(any(), any(), any())).thenAnswer(call -> {
			Set<Long> wanted = Arrays.stream(call.<Long[]>getArgument(2)).collect(Collectors.toSet());

			List<VideoFrameRawResponse> rows = new ArrayList<>();

			for (Map.Entry<Long, List<VideoFrameRawResponse>> entry : videos.entrySet()) {
				if (wanted.contains(entry.getKey())) {
					rows.addAll(entry.getValue());
				}
			}

			return rows;
		});

		when(fingerprints.findVideoCompositionRows(any(), any(), any())).thenAnswer(call -> {
			Set<Long> wanted = Arrays.stream(call.<Long[]>getArgument(2)).collect(Collectors.toSet());

			return videos.entrySet().stream().filter(entry -> wanted.contains(entry.getKey()))
					.map(entry -> new CompositionRow(entry.getValue().getFirst().id(),
							entry.getValue().getFirst().currentFolder()))
					.toList();
		});

		// Every fingerprinted video, eligible or not - the difference the coverage
		// model turns on.
		when(fingerprints.findVideoGateRows(any(), any())).thenAnswer(_ -> videos.values().stream()
				.map(rows -> (VideoGateRow) new StoredVideoGateRow(rows.getFirst().catalogFileId(),
						rows.getFirst().durationSeconds(), rows.getFirst().width(), rows.getFirst().height()))
				.toList());

		when(fingerprints.findVideoFrames(any(), any(), any())).thenAnswer(call -> {
			Set<Long> wanted = Set.of((Long[]) call.getArgument(2));

			List<VideoFrameRow> rows = new ArrayList<>();

			for (Map.Entry<Long, List<VideoFrameRawResponse>> entry : videos.entrySet()) {
				if (!wanted.contains(entry.getKey())) {
					continue;
				}

				for (VideoFrameRawResponse row : entry.getValue()) {
					rows.add(new StoredVideoFrameRow(row.catalogFileId(), row.sampleIndex(), row.phash(),
							row.luminance()));
				}
			}

			return rows;
		});
	}
}