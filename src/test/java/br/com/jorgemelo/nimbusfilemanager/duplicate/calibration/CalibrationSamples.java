package br.com.jorgemelo.nimbusfilemanager.duplicate.calibration;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataConstants;

/**
 * The 32x32 luminance samples, fetched either wholesale or for a chosen set of
 * files - the two strategies the spike exists to compare.
 *
 * <p>
 * Which one wins is not obvious from the sizes. Loading everything is 120 MB of
 * bytes for a library of 120k photos, which a desktop JVM absorbs without
 * complaint, and it is one sequential read the database is good at. Loading only
 * what the candidates need is less data, but only if the candidates touch few
 * files - and a photo needs to appear in just one candidate pair to be required.
 * The saving is real only if most of the library takes part in nothing.
 *
 * <p>
 * Returned as one array per photo rather than one flat block: SSIM takes a
 * {@code byte[]} per image, and slicing a flat block would allocate two copies
 * per pair, which at over a million pairs would measure the allocator instead of
 * the comparison. The per-object overhead is a few megabytes against the 120 the
 * data itself costs.
 */
public final class CalibrationSamples {

	private static final String ALL = """
			SELECT m.id, f.sample_bytes
			FROM media_fingerprint f
			JOIN catalog_file m ON m.id = f.catalog_file_id
			WHERE f.kind = 'PHOTO_PHASH' AND f.algorithm = ?
			  AND f.sample_bytes IS NOT NULL
			""";

	private static final String BY_ID = """
			SELECT m.id, f.sample_bytes
			FROM media_fingerprint f
			JOIN catalog_file m ON m.id = f.catalog_file_id
			WHERE f.kind = 'PHOTO_PHASH' AND f.algorithm = ?
			  AND f.sample_bytes IS NOT NULL
			  AND m.id = ANY(?)
			""";

	private CalibrationSamples() {
	}

	/** Strategy A: one sequential pass over the whole library. */
	public static Map<Long, byte[]> loadAll(String algorithm) throws Exception {
		try (Connection connection = CalibrationDatabase.openReadOnly();
				PreparedStatement statement = connection.prepareStatement(ALL)) {
			statement.setString(1, algorithm);

			return read(statement);
		}
	}

	/** Strategy B: only the files some candidate pair actually needs. */
	public static Map<Long, byte[]> loadOnly(String algorithm, long[] wanted) throws Exception {
		try (Connection connection = CalibrationDatabase.openReadOnly();
				PreparedStatement statement = connection.prepareStatement(BY_ID)) {
			Long[] boxed = new Long[wanted.length];

			for (int index = 0; index < wanted.length; index++) {
				boxed[index] = wanted[index];
			}

			Array ids = connection.createArrayOf("bigint", boxed);

			statement.setString(1, algorithm);
			statement.setArray(2, ids);

			return read(statement);
		}
	}

	private static Map<Long, byte[]> read(PreparedStatement statement) throws Exception {
		Map<Long, byte[]> samples = new HashMap<>(200_000);

		statement.setFetchSize(5_000);

		try (ResultSet rows = statement.executeQuery()) {
			while (rows.next()) {
				byte[] sample = rows.getBytes(2);

				if (sample.length == MetadataConstants.SAMPLE_BYTES) {
					samples.put(rows.getLong(1), sample);
				}
			}
		}

		return samples;
	}
}