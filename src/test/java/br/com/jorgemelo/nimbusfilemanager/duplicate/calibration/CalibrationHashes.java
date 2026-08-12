package br.com.jorgemelo.nimbusfilemanager.duplicate.calibration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;

/**
 * Every eligible photo hash, and nothing else.
 *
 * <p>
 * The luminance samples are deliberately not selected. They are 1024 bytes each
 * against the hash's 32, so loading them for the whole library would cost 126 MB
 * where this costs under 4 - and the scan this feeds never needs them, because
 * SSIM only ever runs on the pairs that survive the distance filter. That
 * separation is itself the hypothesis under test: that the in-memory cap exists
 * to bound the samples, not the arithmetic.
 *
 * <p>
 * Flattened into a single {@code long[]}, four per hash, rather than an array of
 * arrays. 120k hashes are 3,8 MB laid out contiguously, which fits in cache and
 * is read sequentially by the inner loop; an array of 120k separate byte arrays
 * would be 120k pointer dereferences into scattered memory, and the measurement
 * would be of the allocator rather than of the algorithm.
 */
public final class CalibrationHashes {

	private static final int LONGS_PER_HASH = 4;
	private static final int HASH_BYTES = 32;

	/**
	 * The eligibility predicate of {@code countEligibleForSimilarity}, unchanged -
	 * the population measured has to be the population the product would analyse.
	 * No {@code LIMIT}: the point is the whole library, which is what the sampled
	 * runs could not see.
	 */
	private static final String QUERY = """
			SELECT m.id, f.hash_bytes
			FROM media_fingerprint f
			JOIN catalog_file m ON m.id = f.catalog_file_id
			LEFT JOIN catalog_file_location l ON l.catalog_file_id = m.id
			WHERE f.kind = 'PHOTO_PHASH' AND f.algorithm = ?
			  AND m.lifecycle_status = 'ACTIVE'
			  AND f.hash_bytes IS NOT NULL
			  AND NOT EXISTS (SELECT 1 FROM duplicate_exclusion_file fe
			                  WHERE fe.catalog_file_id = m.id AND fe.content_revision = m.content_revision)
			  AND NOT EXISTS (SELECT 1 FROM duplicate_folder_exclusion fo
			                  WHERE REPLACE(l.current_folder, chr(92), '/') = fo.folder_path
			                     OR REPLACE(l.current_folder, chr(92), '/')
			                        LIKE REPLACE(REPLACE(fo.folder_path, '%', chr(92) || '%'), '_', chr(92) || '_') || '/%'
			                        ESCAPE chr(92))
			ORDER BY m.id
			""";

	private CalibrationHashes() {
	}

	public static LoadedHashes load(String algorithm) throws Exception {
		long[] packed = new long[1024 * LONGS_PER_HASH];
		long[] ids = new long[1024];

		int count = 0;
		int malformed = 0;

		try (Connection connection = CalibrationDatabase.openReadOnly();
				PreparedStatement statement = connection.prepareStatement(QUERY)) {
			statement.setString(1, algorithm);
			statement.setFetchSize(10_000);

			try (ResultSet rows = statement.executeQuery()) {
				while (rows.next()) {
					byte[] hash = rows.getBytes(2);

					if (hash.length != HASH_BYTES) {
						malformed++;

						continue;
					}

					if ((count + 1) * LONGS_PER_HASH > packed.length) {
						packed = Arrays.copyOf(packed, packed.length * 2);
						ids = Arrays.copyOf(ids, ids.length * 2);
					}

					ids[count] = rows.getLong(1);

					pack(hash, packed, count * LONGS_PER_HASH);
					count++;
				}
			}
		}

		return new LoadedHashes(Arrays.copyOf(packed, count * LONGS_PER_HASH), Arrays.copyOf(ids, count), count,
				malformed);
	}

	private static void pack(byte[] hash, long[] target, int offset) {
		for (int word = 0; word < LONGS_PER_HASH; word++) {
			long value = 0;

			for (int index = 0; index < 8; index++) {
				value = (value << 8) | (hash[word * 8 + index] & 0xFFL);
			}

			target[offset + word] = value;
		}
	}
}