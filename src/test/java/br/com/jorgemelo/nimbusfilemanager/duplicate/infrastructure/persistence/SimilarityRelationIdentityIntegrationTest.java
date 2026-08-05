package br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.FingerprintAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.RelationParameters;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityRelationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

/**
 * What identifies a relation, proved against the database that enforces it.
 *
 * <p>
 * A relation is a fact about two files under the parameters that produced it.
 * Photos need three of them; videos need five more, and rather than five columns
 * nobody but videos would fill, V28 added one digest column that holds whatever
 * else the medium's comparison depends on - empty for photos, derived for
 * videos. Everything below is a consequence of that being the real key: two
 * configurations must not fight over one row, a rebuild of one must not delete
 * the other, and a photo and a video sharing a catalog id must not invalidate
 * each other.
 *
 * <p>
 * The constraints are asserted from the catalogue rather than assumed, because
 * the conceptual identity and the one PostgreSQL enforces are two different
 * things and only the second one stops a bad write.
 */
class SimilarityRelationIdentityIntegrationTest extends SharedPostgresIntegrationTest {

	private static final String PHOTO_ALGORITHM = FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1;
	private static final String VIDEO_ALGORITHM = FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_FRAMES_V1;

	private static final int RADIUS = 96;
	private static final int MINIMUM = 90;

	/** Two video configurations - a different quorum, say - are two digests. */
	private static final String VIDEO_DIGEST = "video-quorum-3";
	private static final String OTHER_VIDEO_DIGEST = "video-quorum-5";

	@Autowired
	private SimilarityRelationWriter writer;

	@Autowired
	private SimilarityRelationRepository relationRepository;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * The key PostgreSQL enforces has to be the key the design describes. If the
	 * unique constraint or the coverage primary key had not gained the column, two
	 * video configurations would silently overwrite each other and every test below
	 * would pass by accident.
	 */
	@Test
	void theEnforcedKeyIncludesTheDigest() {
		assertThat(uniqueColumns("similarity_relation", "uk_similarity_relation")).containsExactly("algorithm_id",
				"max_distance", "min_similarity", "relation_digest", "first_catalog_file_id",
				"second_catalog_file_id");

		assertThat(uniqueColumns("similarity_relation_coverage", "pk_similarity_relation_coverage"))
				.containsExactly("algorithm_id", "max_distance", "min_similarity", "relation_digest",
						"catalog_file_id");
	}

	/**
	 * A row written the way V27 wrote them - naming no digest at all - lands with
	 * the empty one, which is what the {@code DEFAULT ''} of the migration does to
	 * every photo relation an installation already had. It is then read by the
	 * photo query unchanged, so upgrading costs no recomputation.
	 */
	@Test
	void aRowFromBeforeTheColumnExistedKeepsItsIdentity() {
		long first = catalogued(FileType.PHOTO);
		long second = catalogued(FileType.PHOTO);

		jdbcTemplate.update("""
				INSERT INTO similarity_relation (algorithm_id, max_distance, min_similarity, first_catalog_file_id,
					second_catalog_file_id, similarity_percent, computed_at)
				VALUES (?, ?, ?, ?, ?, ?, now())
				""", PHOTO_ALGORITHM, RADIUS, MINIMUM, Math.min(first, second), Math.max(first, second), 97);

		jdbcTemplate.update("""
				INSERT INTO similarity_relation_coverage (algorithm_id, max_distance, min_similarity, catalog_file_id,
					covered_at)
				VALUES (?, ?, ?, ?, now())
				""", PHOTO_ALGORITHM, RADIUS, MINIMUM, first);

		assertThat(digests()).containsExactly("");

		assertThat(relationRepository.findEligibleRelations(PHOTO_ALGORITHM, RADIUS, MINIMUM,
				RelationParameters.NO_MEDIA_PARAMETERS, new Long[] { first, second })).hasSize(1);

		assertThat(relationRepository.findCovered(PHOTO_ALGORITHM, RADIUS, MINIMUM,
				RelationParameters.NO_MEDIA_PARAMETERS)).containsExactly(first);
	}

	/** Photos keep writing the empty digest, so nothing about them moved. */
	@Test
	void aPhotoRebuildStillWritesTheEmptyDigest() {
		long first = catalogued(FileType.PHOTO);
		long second = catalogued(FileType.PHOTO);

		writer.replaceAll(photo(), new int[] { 0 }, new int[] { 1 }, new int[] { 97 }, 1,
				new long[] { first, second });

		assertThat(digests()).containsExactly("");
	}

	/**
	 * Two video configurations are two sets of facts about the same pairs, and both
	 * are stored. Without the column the second write would have overwritten the
	 * first and an analysis would have served an answer computed under settings
	 * nobody was using.
	 */
	@Test
	void twoVideoConfigurationsCoexist() {
		long first = catalogued(FileType.VIDEO);
		long second = catalogued(FileType.VIDEO);

		long[] ids = { first, second };

		writer.replaceAll(video(VIDEO_DIGEST), new int[] { 0 }, new int[] { 1 }, new int[] { 97 }, 1, ids);
		writer.replaceAll(video(OTHER_VIDEO_DIGEST), new int[] { 0 }, new int[] { 1 }, new int[] { 71 }, 1, ids);

		assertThat(count()).isEqualTo(2);

		assertThat(scoreOf(VIDEO_DIGEST, first, second)).isEqualTo(97);
		assertThat(scoreOf(OTHER_VIDEO_DIGEST, first, second)).isEqualTo(71);
	}

	/**
	 * A rebuild replaces its own family and nothing else - which is what makes
	 * changing one setting cost one recomputation instead of all of them.
	 */
	@Test
	void aRebuildOfOneDigestLeavesTheOtherAlone() {
		long first = catalogued(FileType.VIDEO);
		long second = catalogued(FileType.VIDEO);

		long[] ids = { first, second };

		writer.replaceAll(video(VIDEO_DIGEST), new int[] { 0 }, new int[] { 1 }, new int[] { 97 }, 1, ids);
		writer.replaceAll(video(OTHER_VIDEO_DIGEST), new int[] { 0 }, new int[] { 1 }, new int[] { 71 }, 1, ids);

		writer.replaceAll(video(VIDEO_DIGEST), new int[] {}, new int[] {}, new int[] {}, 0, ids);

		assertThat(scoreOf(VIDEO_DIGEST, first, second)).as("its own family was replaced").isNull();
		assertThat(scoreOf(OTHER_VIDEO_DIGEST, first, second)).as("the other family is untouched").isEqualTo(71);

		assertThat(relationRepository.findCovered(VIDEO_ALGORITHM, RADIUS, MINIMUM, OTHER_VIDEO_DIGEST))
				.as("and so is its coverage").containsExactly(first, second);
	}

	/** An arrival adds inside its family and cannot reach a neighbouring one. */
	@Test
	void anArrivalWritesOnlyInsideItsFamily() {
		long first = catalogued(FileType.VIDEO);
		long second = catalogued(FileType.VIDEO);

		long[] ids = { first, second };

		writer.save(video(VIDEO_DIGEST), new int[] { 0 }, new int[] { 1 }, new int[] { 97 }, 1, ids, ids);

		assertThat(scoreOf(OTHER_VIDEO_DIGEST, first, second)).isNull();
		assertThat(relationRepository.findCovered(VIDEO_ALGORITHM, RADIUS, MINIMUM, OTHER_VIDEO_DIGEST)).isEmpty();
	}

	/**
	 * The case the scoped {@code forget} exists for: one catalog row, a photo
	 * relation and a video relation, and the video's measurement read again. The
	 * video's facts go; the photo's stay, because nothing about the photo changed
	 * and its coverage disappearing would force a recomputation nobody asked for.
	 */
	@Test
	void forgettingAVideoDoesNotReachThePhotoRelationsOfTheSameFile() {
		long shared = catalogued(FileType.VIDEO);
		long photoNeighbour = catalogued(FileType.PHOTO);
		long videoNeighbour = catalogued(FileType.VIDEO);

		writer.save(photo(), new int[] { 0 }, new int[] { 1 }, new int[] { 97 }, 1,
				new long[] { shared, photoNeighbour }, new long[] { shared, photoNeighbour });

		writer.save(video(VIDEO_DIGEST), new int[] { 0 }, new int[] { 1 }, new int[] { 88 }, 1,
				new long[] { shared, videoNeighbour }, new long[] { shared, videoNeighbour });

		assertThat(writer.forget(VIDEO_ALGORITHM, shared)).isEqualTo(1);

		assertThat(scoreOf(VIDEO_DIGEST, shared, videoNeighbour)).isNull();
		assertThat(scoreOf(RelationParameters.NO_MEDIA_PARAMETERS, shared, photoNeighbour))
				.as("the photo relation of the same catalog row survives").isEqualTo(97);

		assertThat(relationRepository.findCovered(PHOTO_ALGORITHM, RADIUS, MINIMUM,
				RelationParameters.NO_MEDIA_PARAMETERS)).as("and so does its coverage")
				.contains(shared, photoNeighbour);

		assertThat(relationRepository.findCovered(VIDEO_ALGORITHM, RADIUS, MINIMUM, VIDEO_DIGEST))
				.as("while the video is back to being a file nobody compared").containsExactly(videoNeighbour);
	}

	/**
	 * A file deleted for good takes its relations and its coverage with it - they
	 * are facts about a file that no longer exists. An excluded file is untouched,
	 * which is the difference {@code readsOnlyRelationsWhoseBothFilesAreEligible}
	 * in the writer's own test holds.
	 */
	@Test
	void deletingTheCatalogRowCascadesToBothTables() {
		long first = catalogued(FileType.VIDEO);
		long second = catalogued(FileType.VIDEO);

		long[] ids = { first, second };

		writer.save(video(VIDEO_DIGEST), new int[] { 0 }, new int[] { 1 }, new int[] { 88 }, 1, ids, ids);

		catalogFileRepository.deleteById(second);
		catalogFileRepository.flush();

		assertThat(count()).isZero();
		assertThat(relationRepository.findCovered(VIDEO_ALGORITHM, RADIUS, MINIMUM, VIDEO_DIGEST))
				.containsExactly(first);
	}

	@Test
	void refusesTheReversedSpellingOfAPair() {
		long first = catalogued(FileType.VIDEO);
		long second = catalogued(FileType.VIDEO);

		assertThatThrownBy(() -> insertRaw(VIDEO_DIGEST, Math.max(first, second), Math.min(first, second)))
				.hasMessageContaining("ck_similarity_relation_canonical");
	}

	@Test
	void refusesAFileRelatedToItself() {
		long only = catalogued(FileType.VIDEO);

		assertThatThrownBy(() -> insertRaw(VIDEO_DIGEST, only, only))
				.hasMessageContaining("ck_similarity_relation_canonical");
	}

	@Test
	void refusesTheSamePairTwiceInsideOneFamily() {
		long first = catalogued(FileType.VIDEO);
		long second = catalogued(FileType.VIDEO);

		insertRaw(VIDEO_DIGEST, Math.min(first, second), Math.max(first, second));

		assertThatThrownBy(() -> insertRaw(VIDEO_DIGEST, Math.min(first, second), Math.max(first, second)))
				.hasMessageContaining("uk_similarity_relation");
	}

	private void insertRaw(String digest, long left, long right) {
		jdbcTemplate.update("""
				INSERT INTO similarity_relation (algorithm_id, max_distance, min_similarity, relation_digest,
					first_catalog_file_id, second_catalog_file_id, similarity_percent, computed_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, now())
				""", VIDEO_ALGORITHM, RADIUS, MINIMUM, digest, left, right, 90);
	}

	private RelationParameters photo() {
		return new RelationParameters(PHOTO_ALGORITHM, RADIUS, MINIMUM);
	}

	private RelationParameters video(String digest) {
		return new RelationParameters(VIDEO_ALGORITHM, RADIUS, MINIMUM, digest);
	}

	/**
	 * The columns of a unique or primary key constraint, in key order, read from
	 * the catalogue rather than from the migration that was supposed to create it.
	 */
	private List<String> uniqueColumns(String table, String constraint) {
		return jdbcTemplate.queryForList("""
				SELECT a.attname
				FROM pg_constraint c
				JOIN pg_class t ON t.oid = c.conrelid
				JOIN unnest(c.conkey) WITH ORDINALITY AS k(attnum, position) ON true
				JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
				WHERE t.relname = ? AND c.conname = ?
				ORDER BY k.position
				""", String.class, table, constraint);
	}

	private List<String> digests() {
		return jdbcTemplate.queryForList("SELECT DISTINCT relation_digest FROM similarity_relation", String.class);
	}

	private Integer scoreOf(String digest, long left, long right) {
		List<Integer> scores = jdbcTemplate.queryForList("""
				SELECT similarity_percent FROM similarity_relation
				WHERE relation_digest = ? AND first_catalog_file_id = ? AND second_catalog_file_id = ?
				""", Integer.class, digest, Math.min(left, right), Math.max(left, right));

		return scores.isEmpty() ? null : scores.getFirst();
	}

	private int count() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM similarity_relation", Integer.class);
	}

	private long catalogued(FileType fileType) {
		String unique = "identity-" + System.nanoTime();

		CatalogFile file = catalogFileRepository.saveAndFlush(CatalogFile.builder().fileKey(unique)
				.fileName(unique + ".bin").extension("bin").sizeBytes(1L).modifiedAt(LocalDateTime.now())
				.fileType(fileType).build());

		return file.getId();
	}
}