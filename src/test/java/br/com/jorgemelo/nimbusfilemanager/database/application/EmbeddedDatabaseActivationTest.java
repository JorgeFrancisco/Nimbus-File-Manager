package br.com.jorgemelo.nimbusfilemanager.database.application;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.database.domain.enums.EmbeddedDatabaseDecision;

/**
 * Whether this run manages its own PostgreSQL, which is a decision three
 * signals can each answer differently. The cases that matter are the
 * contradictions: getting one of them wrong either takes over a database
 * somebody else owns or leaves the application with none at all.
 */
class EmbeddedDatabaseActivationTest {

	private static final String WINDOWS = "Windows 11";
	private static final String LINUX = "Linux";

	/**
	 * The default, and the same one for a clone and for an installed copy: nobody
	 * who runs this application installed a server first.
	 */
	@Test
	void managesItsOwnClusterWhenNothingSaysOtherwise() {
		Assertions.assertThat(decide(null, null, WINDOWS, true)).isEqualTo(EmbeddedDatabaseDecision.ENABLED);
	}

	/**
	 * Naming a host is naming an owner for the data. A copy pointed at a server has
	 * to keep using it - starting a cluster of its own would leave the catalog
	 * behind without a word.
	 */
	@Test
	void leavesADatabaseConfiguredByHandAlone() {
		Assertions.assertThat(decide(null, "db.example.org", WINDOWS, true))
				.isEqualTo(EmbeddedDatabaseDecision.EXTERNAL_DATABASE_CONFIGURED);
	}

	/**
	 * The setting exists to answer this and nothing else, so it outranks the host -
	 * in both directions. Turning it off is what the suite does: every
	 * {@code @SpringBootTest} already has a container of its own.
	 */
	@Test
	void letsTheExplicitSettingOverrideEveryOtherSignal() {
		Assertions.assertThat(decide("true", "db.example.org", WINDOWS, true))
				.isEqualTo(EmbeddedDatabaseDecision.ENABLED);
		Assertions.assertThat(decide("false", null, WINDOWS, true))
				.isEqualTo(EmbeddedDatabaseDecision.DISABLED_BY_CONFIGURATION);
	}

	/** A blank setting is an unset one, and the next signal decides. */
	@Test
	void treatsABlankSettingAsUnset() {
		Assertions.assertThat(decide("  ", null, WINDOWS, true)).isEqualTo(EmbeddedDatabaseDecision.ENABLED);
	}

	/**
	 * A value nobody can parse turns the cluster off rather than on: refusing to
	 * start a database is recoverable, starting a second one over the same folder
	 * is not.
	 */
	@Test
	void readsAnUnparseableSettingAsOff() {
		Assertions.assertThat(decide("yes", null, WINDOWS, true))
				.isEqualTo(EmbeddedDatabaseDecision.DISABLED_BY_CONFIGURATION);
	}

	@Test
	void refusesToRunWhereTheBinariesAreNotShipped() {
		Assertions.assertThat(decide(null, null, LINUX, true)).isEqualTo(EmbeddedDatabaseDecision.UNSUPPORTED_PLATFORM);
		Assertions.assertThat(decide("true", null, null, true))
				.isEqualTo(EmbeddedDatabaseDecision.UNSUPPORTED_PLATFORM);
	}

	@Test
	void refusesToRunWithoutThePackagedBinaries() {
		Assertions.assertThat(decide(null, null, WINDOWS, false))
				.isEqualTo(EmbeddedDatabaseDecision.BINARIES_MISSING);
	}

	/** Only one outcome runs a cluster; every refusal has to read as off. */
	@Test
	void treatsEveryOutcomeButEnabledAsInactive() {
		for (EmbeddedDatabaseDecision decision : EmbeddedDatabaseDecision.values()) {
			Assertions.assertThat(decision.active()).isEqualTo(decision == EmbeddedDatabaseDecision.ENABLED);
		}
	}

	private EmbeddedDatabaseDecision decide(String configured, String externalDatabase, String operatingSystem,
			boolean binariesPresent) {
		return EmbeddedDatabaseActivation.decide(configured, externalDatabase, operatingSystem, binariesPresent);
	}
}