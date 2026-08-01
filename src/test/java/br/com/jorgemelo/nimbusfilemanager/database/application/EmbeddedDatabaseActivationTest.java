package br.com.jorgemelo.nimbusfilemanager.database.application;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.database.domain.enums.EmbeddedDatabaseDecision;

/**
 * Whether this run manages its own PostgreSQL, which is a decision four signals
 * can each answer differently. The cases that matter are the contradictions:
 * getting one of them wrong either takes over a database somebody else owns or
 * leaves the installed product with no database at all.
 */
class EmbeddedDatabaseActivationTest {

	private static final String WINDOWS = "Windows 11";
	private static final String LINUX = "Linux";
	private static final String LAUNCHER = "C:/Program Files/Nimbus File Manager/Nimbus File Manager.exe";

	@Test
	void managesItsOwnClusterWhenTheCopyWasInstalled() {
		Assertions.assertThat(decide(null, null, LAUNCHER, WINDOWS, true)).isEqualTo(EmbeddedDatabaseDecision.ENABLED);
	}

	@Test
	void staysOutOfTheWayOfABuild() {
		Assertions.assertThat(decide(null, null, null, WINDOWS, true))
				.isEqualTo(EmbeddedDatabaseDecision.DEVELOPMENT_BUILD);
	}

	/**
	 * Naming a host is naming an owner for the data. An installation pointed at a
	 * server has to keep using it - starting a cluster of its own would leave the
	 * catalog behind without a word.
	 */
	@Test
	void leavesADatabaseConfiguredByHandAlone() {
		Assertions.assertThat(decide(null, "db.example.org", LAUNCHER, WINDOWS, true))
				.isEqualTo(EmbeddedDatabaseDecision.EXTERNAL_DATABASE_CONFIGURED);
	}

	/**
	 * The setting exists to answer this and nothing else, so it outranks both the
	 * host and the marker - in both directions.
	 */
	@Test
	void letsTheExplicitSettingOverrideEveryOtherSignal() {
		Assertions.assertThat(decide("true", "db.example.org", null, WINDOWS, true))
				.isEqualTo(EmbeddedDatabaseDecision.ENABLED);
		Assertions.assertThat(decide("false", null, LAUNCHER, WINDOWS, true))
				.isEqualTo(EmbeddedDatabaseDecision.DISABLED_BY_CONFIGURATION);
	}

	/** A blank setting is an unset one, and the next signal decides. */
	@Test
	void treatsABlankSettingAsUnset() {
		Assertions.assertThat(decide("  ", null, LAUNCHER, WINDOWS, true)).isEqualTo(EmbeddedDatabaseDecision.ENABLED);
	}

	/**
	 * A value nobody can parse turns the cluster off rather than on: refusing to
	 * start a database is recoverable, starting a second one over the same folder
	 * is not.
	 */
	@Test
	void readsAnUnparseableSettingAsOff() {
		Assertions.assertThat(decide("yes", null, LAUNCHER, WINDOWS, true))
				.isEqualTo(EmbeddedDatabaseDecision.DISABLED_BY_CONFIGURATION);
	}

	@Test
	void refusesToRunWhereTheBinariesAreNotShipped() {
		Assertions.assertThat(decide(null, null, LAUNCHER, LINUX, true))
				.isEqualTo(EmbeddedDatabaseDecision.UNSUPPORTED_PLATFORM);
		Assertions.assertThat(decide("true", null, null, null, true))
				.isEqualTo(EmbeddedDatabaseDecision.UNSUPPORTED_PLATFORM);
	}

	@Test
	void refusesToRunWithoutThePackagedBinaries() {
		Assertions.assertThat(decide(null, null, LAUNCHER, WINDOWS, false))
				.isEqualTo(EmbeddedDatabaseDecision.BINARIES_MISSING);
	}

	/** Only one outcome runs a cluster; every refusal has to read as off. */
	@Test
	void treatsEveryOutcomeButEnabledAsInactive() {
		for (EmbeddedDatabaseDecision decision : EmbeddedDatabaseDecision.values()) {
			Assertions.assertThat(decision.active()).isEqualTo(decision == EmbeddedDatabaseDecision.ENABLED);
		}
	}

	private EmbeddedDatabaseDecision decide(String configured, String externalDatabase, String installedMarker,
			String operatingSystem, boolean binariesPresent) {
		return EmbeddedDatabaseActivation.decide(configured, externalDatabase, installedMarker, operatingSystem,
				binariesPresent);
	}
}