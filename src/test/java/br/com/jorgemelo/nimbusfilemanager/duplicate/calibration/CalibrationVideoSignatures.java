package br.com.jorgemelo.nimbusfilemanager.duplicate.calibration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.FfmpegLanczosFramesPhashAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.VideoSimilarityAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoFrameHash;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoSignature;

/**
 * The eligible video signatures of the running library, reassembled the way the
 * grouping reassembles them.
 *
 * <p>
 * The eligibility predicate is the production query's, unchanged, so a spike
 * measures the population production would analyse instead of a superset of it.
 * Both video spikes read through here: they ask different questions of the same
 * signatures, and a second copy of this query would be a second definition of
 * what "eligible" means - the kind that drifts once and then answers a question
 * nobody asked.
 *
 * <p>
 * Which fingerprints those are is asked of the production algorithm rather than
 * named here, and a run over a library that has none of them refuses instead of
 * calibrating over an empty pool - see {@link #ALGORITHM} and {@link #load()}.
 */
public final class CalibrationVideoSignatures {

	/**
	 * The algorithm being calibrated: whichever one the product produces today,
	 * asked of the bean that decides it.
	 *
	 * <p>
	 * A calibration is a statement about the fingerprints in the library, and a
	 * fingerprint means nothing apart from the algorithm that produced it. Naming a
	 * version here cost what naming one always costs: when the video fingerprint
	 * moved to reaching its frames by seeking - a new identifier, as any change of
	 * pipeline must be - this went on selecting the family that nothing writes any
	 * more, and would have answered with numbers about the previous product.
	 *
	 * <p>
	 * Built with none of its collaborators because none is reached. An algorithm's
	 * identity is a constant of the class; asking for it neither hashes a video nor
	 * compares two.
	 */
	private static final VideoSimilarityAlgorithm ALGORITHM = new FfmpegLanczosFramesPhashAlgorithm(null, null, null);

	/**
	 * The eligibility predicate of the production query plus the frame payload.
	 * Ordered by file then sample index, which is the order the grouping
	 * reassembles signatures from.
	 */
	private static final String QUERY = """
			SELECT m.id, f.sample_index, f.hash_bytes, f.sample_bytes,
			       v.duration_seconds, md.display_width, md.display_height
			FROM media_fingerprint f
			JOIN catalog_file m ON m.id = f.catalog_file_id
			LEFT JOIN catalog_file_location l ON l.catalog_file_id = m.id
			JOIN video v ON v.catalog_file_id = m.id
			LEFT JOIN media_metadata md ON md.catalog_file_id = m.id
			WHERE f.kind = 'VIDEO_PHASH' AND f.algorithm = ?
			  AND m.lifecycle_status = 'ACTIVE'
			  AND NOT EXISTS (SELECT 1 FROM duplicate_exclusion_file fe
			                  WHERE fe.catalog_file_id = m.id AND fe.content_revision = m.content_revision)
			  AND NOT EXISTS (SELECT 1 FROM duplicate_folder_exclusion fo
			                  WHERE REPLACE(l.current_folder, chr(92), '/') = fo.folder_path
			                     OR REPLACE(l.current_folder, chr(92), '/')
			                        LIKE REPLACE(REPLACE(fo.folder_path, '%', chr(92) || '%'), '_', chr(92) || '_') || '/%'
			                        ESCAPE chr(92))
			ORDER BY m.id, f.sample_index
			""";

	private CalibrationVideoSignatures() {
	}

	/**
	 * @throws IllegalStateException when the library carries no fingerprint of the
	 * algorithm in use - see {@link #load(Connection)}
	 */
	public static List<VideoSignature> load() throws Exception {
		try (Connection connection = CalibrationDatabase.openReadOnly()) {
			return load(connection);
		}
	}

	/**
	 * The same, over a connection the caller owns - which is what lets the refusal
	 * be proved against a library a test arranges.
	 *
	 * <p>
	 * <b>Empty is not a population.</b> The query is the eligibility predicate
	 * <em>and</em> a filter on the algorithm, so a library fingerprinted under an
	 * earlier family answers it with no rows at all. Returning that would let a
	 * spike report thresholds, costs and group counts measured over zero videos and
	 * call them a calibration of the current algorithm. It is the one answer here
	 * that is wrong without looking wrong, so it is raised rather than returned:
	 * what the library needs is a rebuild, and nothing else can supply it.
	 */
	static List<VideoSignature> load(Connection connection) throws Exception {
		List<VideoSignature> videos = new ArrayList<>();

		try (PreparedStatement statement = connection.prepareStatement(QUERY)) {
			statement.setString(1, ALGORITHM.algorithm());
			statement.setFetchSize(5_000);

			try (ResultSet rows = statement.executeQuery()) {
				collect(rows, videos);
			}
		}

		if (videos.isEmpty()) {
			throw new IllegalStateException("No eligible video is fingerprinted under " + ALGORITHM.algorithm()
					+ ", the algorithm in use, so there is nothing here to calibrate it with."
					+ " A library still carrying an earlier family has to be fingerprinted again"
					+ " before a spike can say anything about the current one.");
		}

		return videos;
	}

	private static void collect(ResultSet rows, List<VideoSignature> videos) throws Exception {
		long currentId = Long.MIN_VALUE;

		List<VideoFrameHash> frames = new ArrayList<>();
		Double duration = null;
		Integer width = null;
		Integer height = null;

		while (rows.next()) {
			long id = rows.getLong(1);

			if (id != currentId) {
				if (currentId != Long.MIN_VALUE) {
					videos.add(signature(currentId, frames, duration, width, height));
				}

				currentId = id;
				frames = new ArrayList<>();
			}

			frames.add(new VideoFrameHash(rows.getInt(2), rows.getBytes(3), rows.getBytes(4)));

			duration = (Double) rows.getObject(5);
			width = (Integer) rows.getObject(6);
			height = (Integer) rows.getObject(7);
		}

		if (currentId != Long.MIN_VALUE) {
			videos.add(signature(currentId, frames, duration, width, height));
		}
	}

	private static VideoSignature signature(long catalogFileId, List<VideoFrameHash> frames, Double duration,
			Integer width, Integer height) {
		return new VideoSignature(new UUID(0, catalogFileId), List.copyOf(frames), duration, width, height);
	}
}