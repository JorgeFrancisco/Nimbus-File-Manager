package br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.RelationParameters;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityRelationRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.RelationRow;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

/**
 * How a relation stops being true.
 *
 * <p>
 * Storing only approvals has a consequence that is easy to miss: a pair that
 * stops being approved does not appear in the next batch, so nothing overwrites
 * it. Left alone, the row survives as an approval the analysis no longer makes -
 * a photo edited in place keeps its catalog id, drops from 97 to 60 against a
 * neighbour, and the two would be grouped together for good.
 *
 * <p>
 * Two mechanisms answer it, and these tests hold both: a rebuild replaces the
 * whole set for its parameters, and a re-fingerprinted photo has its relations
 * forgotten before anything is recomputed. Neither touches an excluded file,
 * because exclusion is not deletion and the point of keeping the relation is to
 * reuse it when the exclusion is lifted.
 */
class SimilarityRelationWriterIntegrationTest extends SharedPostgresIntegrationTest {

	private static final String ALGORITHM = "DCT_PHASH_256_V1";
	private static final int RADIUS = 96;
	private static final int MINIMUM = 95;

	@Autowired
	private SimilarityRelationWriter writer;

	@Autowired
	private SimilarityRelationRepository relationRepository;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * The regression the whole design turns on: A-B is approved at 97, A is edited,
	 * and the new analysis scores the pair at 60 - below the threshold, so it is
	 * simply absent from the new batch. The old row must not survive.
	 */
	@Test
	void aRelationThatStopsBeingApprovedDoesNotSurviveTheRebuild() {
		long first = catalogued();
		long second = catalogued();
		long third = catalogued();

		long[] ids = { first, second, third };

		writer.replaceAll(parameters(MINIMUM), new int[] { 0, 1 }, new int[] { 1, 2 }, new int[] { 97, 96 }, 2,
				ids);

		assertThat(count()).as("both pairs approved to begin with").isEqualTo(2);

		// A is edited. The next analysis approves only B-C: A-B now scores 60.
		writer.replaceAll(parameters(MINIMUM), new int[] { 1 }, new int[] { 2 }, new int[] { 96 }, 1, ids);

		List<RelationRow> surviving = relations(ids);

		assertThat(surviving).as("the pair that stopped being approved is gone").hasSize(1);
		assertThat(surviving.getFirst().getFirstCatalogFileId()).isEqualTo(second);
		assertThat(surviving.getFirst().getSecondCatalogFileId()).isEqualTo(third);
	}

	/**
	 * Forgetting one photo's relations, which is what a re-fingerprint needs before
	 * recomputing: everything computed from the old image goes, everything else
	 * stays.
	 */
	@Test
	void forgettingAPhotoRemovesItsRelationsFromBothSides() {
		long first = catalogued();
		long second = catalogued();
		long third = catalogued();

		long[] ids = { first, second, third };

		writer.replaceAll(parameters(MINIMUM), new int[] { 0, 1, 0 }, new int[] { 1, 2, 2 },
				new int[] { 97, 96, 98 }, 3, ids);

		assertThat(writer.forget(ALGORITHM, second)).as("the two relations naming it, from either side").isEqualTo(2);

		List<RelationRow> surviving = relations(ids);

		assertThat(surviving).hasSize(1);
		assertThat(surviving.getFirst().getFirstCatalogFileId()).isEqualTo(first);
		assertThat(surviving.getFirst().getSecondCatalogFileId()).isEqualTo(third);
	}

	/**
	 * A rebuild replaces its own parameter set and nothing else. An analysis at
	 * SSIM 90 must not lose what one at 95 computed, or every threshold change
	 * would cost every other threshold its work.
	 */
	@Test
	void replacingOneParameterSetLeavesTheOthersAlone() {
		long first = catalogued();
		long second = catalogued();

		long[] ids = { first, second };

		writer.replaceAll(parameters(95), new int[] { 0 }, new int[] { 1 }, new int[] { 97 }, 1, ids);
		writer.replaceAll(parameters(90), new int[] { 0 }, new int[] { 1 }, new int[] { 97 }, 1, ids);

		assertThat(count()).isEqualTo(2);

		writer.replaceAll(parameters(95), new int[] {}, new int[] {}, new int[] {}, 0, ids);

		assertThat(count()).as("only the set being rebuilt was replaced").isEqualTo(1);
	}

	/** Writing the same set twice is a no-op, not a constraint violation. */
	@Test
	void writingTheSameRelationTwiceUpdatesItInsteadOfFailing() {
		long first = catalogued();
		long second = catalogued();

		long[] ids = { first, second };

		writer.save(parameters(MINIMUM), new int[] { 0 }, new int[] { 1 }, new int[] { 97 }, 1, ids, ids);
		writer.save(parameters(MINIMUM), new int[] { 0 }, new int[] { 1 }, new int[] { 91 }, 1, ids, ids);

		assertThat(count()).isEqualTo(1);
		assertThat(relations(ids).getFirst().getSimilarityPercent()).as("the newer score wins").isEqualTo(91);
	}

	/** The pair is stored canonically however the caller ordered it. */
	@Test
	void storesThePairInCatalogOrderWhicheverWayItArrives() {
		long first = catalogued();
		long second = catalogued();

		writer.save(parameters(MINIMUM), new int[] { 0 }, new int[] { 1 }, new int[] { 97 }, 1,
				new long[] { second, first }, new long[] { second, first });

		RelationRow stored = relations(new long[] { first, second }).getFirst();

		assertThat(stored.getFirstCatalogFileId()).isLessThan(stored.getSecondCatalogFileId());
	}

	/** Only relations whose two files are eligible reach the grouping. */
	@Test
	void readsOnlyRelationsWhoseBothFilesAreEligible() {
		long first = catalogued();
		long second = catalogued();
		long third = catalogued();

		writer.replaceAll(parameters(MINIMUM), new int[] { 0, 1 }, new int[] { 1, 2 }, new int[] { 97, 96 }, 2,
				new long[] { first, second, third });

		assertThat(relations(new long[] { first, second, third })).hasSize(2);
		assertThat(relations(new long[] { first, second })).as("dropping the third file drops its relation")
				.hasSize(1);
		assertThat(count()).as("but the row is still stored, ready for the exclusion to be lifted").isEqualTo(2);
	}

	private RelationParameters parameters(int minSimilarity) {
		return new RelationParameters(ALGORITHM, RADIUS, minSimilarity);
	}

	private List<RelationRow> relations(long[] eligible) {
		Long[] boxed = new Long[eligible.length];

		for (int index = 0; index < eligible.length; index++) {
			boxed[index] = eligible[index];
		}

		return relationRepository.findEligibleRelations(ALGORITHM, RADIUS, MINIMUM,
				RelationParameters.NO_MEDIA_PARAMETERS, boxed);
	}

	private int count() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM similarity_relation", Integer.class);
	}

	private long catalogued() {
		String unique = "relation-" + System.nanoTime();

		CatalogFile file = catalogFileRepository.saveAndFlush(CatalogFile.builder().fileKey(unique)
				.fileName(unique + ".jpg").extension("jpg").sizeBytes(1L).modifiedAt(LocalDateTime.now())
				.fileType(FileType.PHOTO).build());

		return file.getId();
	}
}