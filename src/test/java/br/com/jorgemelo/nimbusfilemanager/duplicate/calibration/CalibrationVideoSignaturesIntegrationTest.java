package br.com.jorgemelo.nimbusfilemanager.duplicate.calibration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceUtils;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.FfmpegLanczosFramesPhashAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.FingerprintAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoSignature;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

/**
 * What the calibration reads, and what it refuses to read.
 *
 * <p>
 * A spike exists to put a number on the product as it is - thresholds, costs,
 * how many groups a library yields - and every one of those numbers is a
 * statement about one algorithm. So the tool asks the production bean which
 * algorithm that is, and these hold it to both halves of the consequence: it
 * reads the fingerprints of the algorithm in use, and it refuses a library that
 * carries only an earlier family rather than reporting the calibration of an
 * empty pool.
 *
 * <p>
 * The identifier is never spelled out on the reading side here either. It is
 * taken from the same bean the tool takes it from, so the day the video
 * fingerprint moves again these move with it - whereas a test naming the
 * constant would go on passing while the tool selected a family nothing writes,
 * which is precisely the failure being closed.
 */
class CalibrationVideoSignaturesIntegrationTest extends SharedPostgresIntegrationTest {

	/** Asked of the bean that decides it, exactly as the tool asks. */
	private static final String IN_USE = new FfmpegLanczosFramesPhashAlgorithm(null, null, null).algorithm();

	/**
	 * The family the 188 videos of the real library were fingerprinted under before
	 * the frames were reached by seeking - named because versioning is what this
	 * asserts, not as a stand-in for whatever is current.
	 */
	private static final String EARLIER_FAMILY = FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_FRAMES_V1;

	private static final int FRAMES = 5;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private MediaFingerprintRepository mediaFingerprintRepository;

	@Autowired
	private DataSource dataSource;

	@Test
	void readsTheVideosFingerprintedUnderTheAlgorithmInUse() throws Exception {
		CatalogFile video = video(41.5);

		fingerprint(video, IN_USE);

		List<VideoSignature> signatures = load();

		Assertions.assertThat(signatures).hasSize(1);
		Assertions.assertThat(signatures.getFirst().id()).isEqualTo(new UUID(0, video.getId()));
		Assertions.assertThat(signatures.getFirst().frames()).hasSize(FRAMES);
		Assertions.assertThat(signatures.getFirst().durationSeconds()).isEqualTo(41.5);
	}

	/**
	 * The library of the day the algorithm changed: every video fingerprinted, none
	 * of them under the algorithm now in use. Answering that with an empty list
	 * would let a spike publish thresholds measured over no videos at all and
	 * present them as a calibration of the current one.
	 */
	@Test
	void refusesToCalibrateALibraryThatCarriesOnlyTheEarlierFamily() throws Exception {
		Assertions.assertThat(IN_USE).as("the premise of this test is that the two differ")
				.isNotEqualTo(EARLIER_FAMILY);

		fingerprint(video(41.5), EARLIER_FAMILY);

		Assertions.assertThatThrownBy(this::load).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(IN_USE);
	}

	/**
	 * And where both families exist, the population is one of them. A pool built
	 * from two incompatible halves would be worse than an empty one: it would look
	 * like an answer.
	 */
	@Test
	void neverBuildsItsPopulationOutOfTwoFamilies() throws Exception {
		CatalogFile current = video(41.5);
		CatalogFile earlier = video(12.0);

		fingerprint(current, IN_USE);
		fingerprint(earlier, EARLIER_FAMILY);

		List<VideoSignature> signatures = load();

		Assertions.assertThat(signatures).hasSize(1);
		Assertions.assertThat(signatures.getFirst().id()).isEqualTo(new UUID(0, current.getId()));
	}

	/** The tool's own query, over the connection this test's writes live on. */
	private List<VideoSignature> load() throws Exception {
		Connection connection = DataSourceUtils.getConnection(dataSource);

		try {
			return CalibrationVideoSignatures.load(connection);
		} finally {
			DataSourceUtils.releaseConnection(connection, dataSource);
		}
	}

	/** The five rows one video fingerprint is made of, under one algorithm. */
	private void fingerprint(CatalogFile video, String algorithm) {
		for (int sample = 0; sample < FRAMES; sample++) {
			mediaFingerprintRepository.save(MediaFingerprint.builder().catalogFileId(video.getId())
					.kind(FingerprintKind.VIDEO_PHASH).algorithm(algorithm).sampleIndex(sample)
					.positionMs(sample * 1000L).hashBytes(new byte[32]).sampleBytes(new byte[1024])
					.computedAt(LocalDateTime.now()).build());
		}

		mediaFingerprintRepository.flush();
	}

	/**
	 * A catalogued video with the duration row the calibration joins on - written
	 * as a row rather than through the aggregate, because what is under test is a
	 * query and the query reads tables.
	 */
	private CatalogFile video(Double durationSeconds) throws Exception {
		String folder = "D:" + backslash() + "library";
		String path = folder + backslash() + System.nanoTime() + ".mp4";

		CatalogFile file = CatalogFile.builder().extension("mp4").sizeBytes(1L).modifiedAt(Instant.now())
				.fileType(FileType.VIDEO).build();

		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(path).currentFolder(folder)
				.pathFlavor(PathFlavor.WINDOWS).build());

		CatalogFile catalogued = CatalogFiles.catalogued(catalogFileRepository, catalogFileLocationRepository, file);

		Connection connection = DataSourceUtils.getConnection(dataSource);

		try (PreparedStatement statement = connection
				.prepareStatement("INSERT INTO video (catalog_file_id, duration_seconds) VALUES (?, ?)")) {
			statement.setLong(1, catalogued.getId());
			statement.setObject(2, durationSeconds);

			statement.executeUpdate();
		} finally {
			DataSourceUtils.releaseConnection(connection, dataSource);
		}

		return catalogued;
	}

	private String backslash() {
		return String.valueOf((char) 92);
	}
}