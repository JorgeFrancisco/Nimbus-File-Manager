package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.CompositionRow;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoHashRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.VideoFrameRawResponse;

/**
 * The application decides <em>what</em> to ask for; the worker decides what it
 * actually analysed. Both answers are a digest, both are written to the same
 * row, and the screen compares them to say "outdated" - so if the two ever
 * computed the digest differently, every published result would look stale
 * forever, or none would.
 *
 * <p>
 * They agree because there is only one selection: this primitive, run over rows
 * the two queries order identically. These tests hold that single point rather
 * than a duplicated one on each side - including the case the video path made
 * necessary, where the heavy query returns one row per frame and the cap can cut
 * a video in the middle of its frames.
 */
class SimilarityCompositionAgreementTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.MAY, 1, 10, 0);

	private final DuplicateExclusionService exclusions = mock(DuplicateExclusionService.class);

	@BeforeEach
	void nothingIsExcluded() {
		when(exclusions.excludedFilePublicIds()).thenReturn(List.of());
		when(exclusions.excludedFolders()).thenReturn(List.of());
	}

	@Test
	void thePhotoQueriesAndTheHeavyRowsSelectTheSameFiles() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		List<CompositionRow> light = List.of(new CompositionRow(first, "C:/Fotos"),
				new CompositionRow(second, "C:/Outros"));

		List<PhotoHashRawResponse> heavy = List.of(photo(first, "C:/Fotos"), photo(second, "C:/Outros"));

		Assertions.assertThat(digestOfHeavyPhotos(heavy)).isEqualTo(digestOf(light));
	}

	@Test
	void aVideoSpreadOverSeveralFrameRowsCountsOnce() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		List<CompositionRow> light = List.of(new CompositionRow(first, "C:/Videos"),
				new CompositionRow(second, "C:/Videos"));

		List<VideoFrameRawResponse> frames = List.of(frame(first, 0), frame(first, 1), frame(first, 2),
				frame(second, 0), frame(second, 1));

		Assertions.assertThat(digestOfFrames(frames)).isEqualTo(digestOf(light));
	}

	@Test
	void aVideoCutInTheMiddleOfItsFramesByTheCapIsStillTheSameSelectionOnBothSides() {
		UUID first = UUID.randomUUID();
		UUID cut = UUID.randomUUID();

		// The row cap falls between two frames of the second video. Both queries
		// truncate at the same row because both order by (file, frame), so the light
		// query lists the video the heavy one only half-read - and the digests match.
		List<VideoFrameRawResponse> frames = List.of(frame(first, 0), frame(first, 1), frame(cut, 0));

		List<CompositionRow> light = List.of(new CompositionRow(first, "C:/Videos"),
				new CompositionRow(cut, "C:/Videos"));

		Assertions.assertThat(digestOfFrames(frames)).isEqualTo(digestOf(light));
	}

	@Test
	void aFileHiddenByAnExclusionLeavesBothSidesTogether() {
		UUID kept = UUID.randomUUID();
		UUID excluded = UUID.randomUUID();

		when(exclusions.excludedFilePublicIds()).thenReturn(List.of(excluded));

		List<CompositionRow> light = List.of(new CompositionRow(kept, "C:/Fotos"),
				new CompositionRow(excluded, "C:/Fotos"));

		List<PhotoHashRawResponse> heavy = List.of(photo(kept, "C:/Fotos"), photo(excluded, "C:/Fotos"));

		Assertions.assertThat(digestOfHeavyPhotos(heavy)).isEqualTo(digestOf(light));
		Assertions.assertThat(SimilarityGroupSupport.canonicalComposition(light, CompositionRow::mediaPublicId,
				CompositionRow::currentFolder, exclusions)).hasSize(1);
	}

	@Test
	void excludingAFileChangesTheDigestSoAPublishedResultReportsItselfOutdated() {
		UUID kept = UUID.randomUUID();
		UUID excluded = UUID.randomUUID();

		List<CompositionRow> rows = List.of(new CompositionRow(kept, "C:/Fotos"),
				new CompositionRow(excluded, "C:/Fotos"));

		String before = digestOf(rows);

		when(exclusions.excludedFilePublicIds()).thenReturn(List.of(excluded));

		Assertions.assertThat(digestOf(rows)).isNotEqualTo(before);
	}

	@Test
	void movingAFileToAnotherFolderChangesTheDigestEvenThoughTheFilesAreTheSame() {
		UUID moved = UUID.randomUUID();

		String before = digestOf(List.of(new CompositionRow(moved, "C:/Fotos")));

		Assertions.assertThat(digestOf(List.of(new CompositionRow(moved, "C:/Outros")))).isNotEqualTo(before);
	}

	private String digestOf(List<CompositionRow> rows) {
		List<CompositionRow> selected = SimilarityGroupSupport.canonicalComposition(rows,
				CompositionRow::mediaPublicId, CompositionRow::currentFolder, exclusions);

		return SimilarityDigest.ofComposition(selected.stream().map(CompositionRow::mediaPublicId).toList(),
				selected.stream().map(CompositionRow::currentFolder).toList());
	}

	private String digestOfHeavyPhotos(List<PhotoHashRawResponse> rows) {
		return digestOfSelected(SimilarityGroupSupport.canonicalComposition(rows, PhotoHashRawResponse::id,
				PhotoHashRawResponse::currentFolder, exclusions));
	}

	private String digestOfFrames(List<VideoFrameRawResponse> rows) {
		return digestOfSelected(SimilarityGroupSupport.canonicalComposition(rows, VideoFrameRawResponse::id,
				VideoFrameRawResponse::currentFolder, exclusions));
	}

	private String digestOfSelected(List<CompositionRow> selected) {
		return SimilarityDigest.ofComposition(selected.stream().map(CompositionRow::mediaPublicId).toList(),
				selected.stream().map(CompositionRow::currentFolder).toList());
	}

	private PhotoHashRawResponse photo(UUID id, String folder) {
		return new PhotoHashRawResponse(id.getMostSignificantBits(), id, new byte[32], new byte[64], "photo.jpg",
				"jpg", 100L, folder + "/photo.jpg", folder, NOW);
	}

	private VideoFrameRawResponse frame(UUID id, int sampleIndex) {
		return new VideoFrameRawResponse(id.getLeastSignificantBits(), id, sampleIndex, (long) sampleIndex * 1000,
				new byte[32], new byte[64], "video.mp4", "mp4", 100L, "C:/Videos/video.mp4", "C:/Videos", NOW, 10.0,
				1920, 1080);
	}
}