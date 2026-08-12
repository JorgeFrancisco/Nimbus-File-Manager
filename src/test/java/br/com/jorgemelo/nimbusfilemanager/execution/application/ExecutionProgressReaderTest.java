package br.com.jorgemelo.nimbusfilemanager.execution.application;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * The percentage, per workload, against the rows those workloads actually
 * write.
 *
 * <p>
 * Every case here failed before the progress model existed, and failed the same
 * way: the reader took {@code filesFound} to be the concluded count for
 * everybody, and that field means something different in almost every workload.
 * These are the three it was wrong about, plus the ones it was right about -
 * because a fix that moves the error somewhere else is not a fix.
 */
class ExecutionProgressReaderTest {

	private final ExecutionProgressReader reader = Progress.reader();

	/**
	 * A metadata rebuild reports its concluded count in the second counter and
	 * leaves the first at a constant zero.
	 *
	 * <p>
	 * Reading the first gave 0% for the entire run on the executions screen, while
	 * the settings screen - which reads the second - showed the truth. One run, two
	 * screens, irreconcilable numbers.
	 */
	@Test
	void aMetadataRebuildIsNotStuckAtZero() {
		Execution execution = row(ExecutionType.METADATA_REBUILD, 1_000);

		execution.setFilesFound(0);
		execution.setFilesAnalyzed(250);

		Assertions.assertThat(reader.percent(execution)).isEqualTo(25.0);
	}

	/**
	 * A similarity analysis reports the <em>total</em> in the first counter, so
	 * reading it drew a full bar before a single pair had been compared.
	 */
	@Test
	void aSimilarityAnalysisDoesNotStartAtAHundredPerCent() {
		Execution execution = row(ExecutionType.SIMILARITY_PHOTO, 1_000);

		execution.setFilesFound(1_000);
		execution.setFilesAnalyzed(100);

		Assertions.assertThat(reader.percent(execution)).isEqualTo(10.0);
	}

	/**
	 * An inventory's first counter is discovery, which runs ahead of the analysis
	 * doing the work - so the bar filled while the work continued. What is
	 * concluded is the three outcomes an item can reach.
	 */
	@Test
	void anInventoryMeasuresWhatWasAnalysedRatherThanWhatWasFound() {
		Execution execution = row(ExecutionType.INVENTORY, 1_000);

		execution.setFilesFound(1_000);
		execution.setFilesAnalyzed(200);
		execution.setCacheHits(100);
		execution.setErrors(50);

		Assertions.assertThat(reader.percent(execution)).as("analysed, cached and failed are all concluded")
				.isEqualTo(35.0);
	}

	/** A fingerprint counts what it gave up on, because it will not retry it. */
	@Test
	void aFingerprintCountsWhatItFailedOn() {
		Execution execution = row(ExecutionType.FINGERPRINT_PHOTO, 1_000);

		execution.setFilesFound(400);
		execution.setFilesAnalyzed(400);
		execution.setErrors(100);

		Assertions.assertThat(reader.percent(execution)).isEqualTo(50.0);
	}

	/**
	 * A conversion counts hundredths, so a batch of one long video still advances -
	 * and the total is scaled to match, because comparing hundredths against whole
	 * files would fill the bar on the first file.
	 */
	@Test
	void aConversionAdvancesInsideTheFileItIsWorkingOn() {
		Execution execution = row(ExecutionType.CONVERSION, 4);

		execution.setFilesAnalyzed(1);
		execution.setCurrentItemPercent(50);

		Assertions.assertThat(reader.total(execution)).as("four files, in hundredths").isEqualTo(400L);
		Assertions.assertThat(reader.percent(execution)).isEqualTo(37.5);
	}

	/** An organization reports the concluded count in the first counter. */
	@Test
	void anOrganizationReadsTheCounterItReports() {
		Execution execution = row(ExecutionType.ORGANIZATION, 200);

		execution.setFilesFound(50);
		execution.setFilesAnalyzed(30);

		Assertions.assertThat(reader.percent(execution)).as("processed, not the subset that moved").isEqualTo(25.0);
	}

	/**
	 * A dataset update counts stages, and the bar over them is honest even though
	 * no estimate over them would be.
	 */
	@Test
	void aDatasetUpdateStillDrawsAnHonestBarOverItsStages() {
		Execution execution = row(ExecutionType.GEO_DATASET_UPDATE, 9);

		execution.setFilesFound(6);
		execution.setFilesAnalyzed(6);

		Assertions.assertThat(reader.percent(execution)).isEqualTo(66.7);
	}

	/**
	 * No total means no bar, and null says exactly that. Zero would say work is
	 * happening and getting nowhere.
	 */
	@Test
	void withoutATotalThereIsNoBar() {
		Execution execution = row(ExecutionType.INVENTORY, 0);

		execution.setFilesAnalyzed(100);

		Assertions.assertThat(reader.percent(execution)).isNull();
		Assertions.assertThat(reader.total(execution)).isNull();
	}

	/**
	 * A type nobody declared keeps reading the counter every reader used before
	 * this class existed, so adding the model changed nothing for the workloads
	 * that were never audited.
	 */
	@Test
	void anUndeclaredTypeKeepsTheBehaviourItAlreadyHad() {
		Execution execution = row(ExecutionType.QUARANTINE_RESTORE, 40);

		execution.setFilesFound(10);
		execution.setFilesAnalyzed(4);

		Assertions.assertThat(reader.percent(execution)).isEqualTo(25.0);
	}

	private Execution row(ExecutionType type, int total) {
		return Execution.builder().id(1L).executionType(type).totalExpected(total).build();
	}
}