package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.FingerprintAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoComparisonInputs;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence.SimilarityRelationWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.MediaMetadata;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Video;

/**
 * A video's approved relations were decided by its duration and its display
 * size, and neither lives in its fingerprint. So the two writers that can change
 * them - a re-scan of a file that moved on, and a metadata rebuild asked to
 * refresh dimensions - have to be able to say so, and the answer has to be no
 * whenever nothing actually moved.
 *
 * <p>
 * Both halves matter and they fail in opposite directions. Missing a real change
 * leaves relations that are quietly wrong and a coverage row that entitles every
 * later run to skip the file, so nothing ever notices. Reacting to a write that
 * changed nothing drops the coverage of every video a rebuild visits, which
 * turns the incremental path back into a full one on every pass.
 */
@ExtendWith(MockitoExtension.class)
class VideoRelationInvalidatorTest {

	private static final long CATALOG_FILE_ID = 42L;

	@Mock
	private SimilarityRelationWriter similarityRelationWriter;

	@Test
	void forgetsNothingWhenTheDurationCameBackTheSame() {
		CatalogFile file = video(30.0, 1920, 1080);

		VideoComparisonInputs before = VideoComparisonInputs.of(file);

		assertThat(invalidator().invalidateIfChanged(file, before)).isFalse();

		verifyNoInteractions(similarityRelationWriter);
	}

	@Test
	void forgetsWhenTheDurationMoved() {
		CatalogFile file = video(30.0, 1920, 1080);

		VideoComparisonInputs before = VideoComparisonInputs.of(file);

		file.getVideo().setDurationSeconds(41.5);

		assertThat(invalidator().invalidateIfChanged(file, before)).isTrue();

		verify(similarityRelationWriter).forget(FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_FRAMES_V1,
				CATALOG_FILE_ID);
	}

	@Test
	void forgetsNothingWhenTheDimensionsCameBackTheSame() {
		CatalogFile file = video(30.0, 1920, 1080);

		VideoComparisonInputs before = VideoComparisonInputs.of(file);

		file.getMetadata().setDisplayWidth(1920);
		file.getMetadata().setDisplayHeight(1080);

		assertThat(invalidator().invalidateIfChanged(file, before)).isFalse();

		verifyNoInteractions(similarityRelationWriter);
	}

	/**
	 * A rotation is the case this exists for: the frames are untouched, the file is
	 * the same file, and the aspect gate now answers the opposite question.
	 */
	@Test
	void forgetsWhenTheDisplayShapeMoved() {
		CatalogFile file = video(30.0, 1920, 1080);

		VideoComparisonInputs before = VideoComparisonInputs.of(file);

		file.getMetadata().setDisplayWidth(1080);
		file.getMetadata().setDisplayHeight(1920);

		assertThat(invalidator().invalidateIfChanged(file, before)).isTrue();

		verify(similarityRelationWriter).forget(FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_FRAMES_V1,
				CATALOG_FILE_ID);
	}

	/**
	 * The photo relations of the same catalog row survive, and the scope is what
	 * makes them: forgetting by file alone would cost the other medium a full
	 * recomputation that nothing asked for.
	 */
	@Test
	void neverReachesTheOtherMediumsRelations() {
		CatalogFile file = video(30.0, 1920, 1080);

		VideoComparisonInputs before = VideoComparisonInputs.of(file);

		file.getVideo().setDurationSeconds(31.0);

		invalidator().invalidateIfChanged(file, before);

		verify(similarityRelationWriter).forget(FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_FRAMES_V1,
				CATALOG_FILE_ID);
		verify(similarityRelationWriter, never()).forget(eq(FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1),
				any(Long[].class));
	}

	/**
	 * Metadata arriving where there was none is a change, not an absence of one -
	 * a video with no dimensions passes the aspect gate against everything, and one
	 * with them does not.
	 */
	@Test
	void treatsMetadataAppearingAsAChange() {
		CatalogFile file = video(30.0, null, null);

		VideoComparisonInputs before = VideoComparisonInputs.of(file);

		file.getMetadata().setDisplayWidth(1920);
		file.getMetadata().setDisplayHeight(1080);

		assertThat(invalidator().invalidateIfChanged(file, before)).isTrue();
	}

	/** A row that was never persisted has nothing stored about it to forget. */
	@Test
	void doesNothingForAFileTheCatalogHasNotSavedYet() {
		CatalogFile file = video(30.0, 1920, 1080);

		file.setId(null);

		assertThat(invalidator().invalidateIfChanged(file, new VideoComparisonInputs(1.0, 1, 1))).isFalse();

		verifyNoInteractions(similarityRelationWriter);
	}

	/**
	 * A photo does not become a video by being re-catalogued, and its inputs are
	 * absent both before and after - so the writer is never asked.
	 */
	@Test
	void leavesAFileThatIsNotAVideoAlone() {
		CatalogFile file = new CatalogFile();

		file.setId(CATALOG_FILE_ID);

		assertThat(invalidator().invalidateIfChanged(file, VideoComparisonInputs.of(file))).isFalse();

		verifyNoInteractions(similarityRelationWriter);
	}

	private VideoRelationInvalidator invalidator() {
		return new VideoRelationInvalidator(similarityRelationWriter);
	}

	private CatalogFile video(Double durationSeconds, Integer width, Integer height) {
		CatalogFile file = new CatalogFile();

		file.setId(CATALOG_FILE_ID);

		Video video = new Video();

		video.setDurationSeconds(durationSeconds);

		MediaMetadata metadata = new MediaMetadata();

		metadata.setDisplayWidth(width);
		metadata.setDisplayHeight(height);

		file.setVideo(video);
		file.setMetadata(metadata);

		return file;
	}
}