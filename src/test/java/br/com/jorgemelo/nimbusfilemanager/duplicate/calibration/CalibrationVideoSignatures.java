package br.com.jorgemelo.nimbusfilemanager.duplicate.calibration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.FingerprintAlgorithm;
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
 */
public final class CalibrationVideoSignatures {

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
			  AND NOT EXISTS (SELECT 1 FROM duplicate_file_exclusion fe WHERE fe.public_id = m.public_id)
			  AND NOT EXISTS (SELECT 1 FROM duplicate_folder_exclusion fo
			                  WHERE REPLACE(l.current_folder, chr(92), '/') = fo.folder_path
			                     OR REPLACE(l.current_folder, chr(92), '/')
			                        LIKE REPLACE(REPLACE(fo.folder_path, '%', chr(92) || '%'), '_', chr(92) || '_') || '/%'
			                        ESCAPE chr(92))
			ORDER BY m.id, f.sample_index
			""";

	private CalibrationVideoSignatures() {
	}

	public static List<VideoSignature> load() throws Exception {
		List<VideoSignature> videos = new ArrayList<>();

		try (Connection connection = CalibrationDatabase.openReadOnly();
				PreparedStatement statement = connection.prepareStatement(QUERY)) {
			statement.setString(1, FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_FRAMES_V1);
			statement.setFetchSize(5_000);

			try (ResultSet rows = statement.executeQuery()) {
				collect(rows, videos);
			}
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