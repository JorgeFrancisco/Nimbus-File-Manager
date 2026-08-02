package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * When the application is allowed to ask for administrator, and - more
 * importantly - when it must not.
 *
 * <p>
 * A prompt on every start is the kind of thing people learn to click through,
 * so each refusal below exists to keep one from appearing where it would buy
 * nothing.
 */
class UsnElevationTest {

	private static final String WINDOWS = "Windows 11";
	private static final String LAUNCHER = "C:/Program Files/Nimbus File Manager/Nimbus File Manager.exe";

	/** An installed copy on Windows, not administrator, has the journal to gain. */
	@Test
	void asksWhenTheJournalIsOutOfReachAndThereIsSomethingToRestart() {
		Assertions.assertThat(UsnElevation.shouldRelaunch(true, WINDOWS, LAUNCHER, false, false)).isTrue();
	}

	/** Already administrator: the volume opens, so there is nothing to ask for. */
	@Test
	void staysQuietWhenTheVolumeAlreadyOpens() {
		Assertions.assertThat(UsnElevation.shouldRelaunch(true, WINDOWS, LAUNCHER, false, true)).isFalse();
	}

	/**
	 * The marker the restart carries. Without it, a volume that stays unreadable
	 * even to an administrator - a network drive, a filesystem with no journal -
	 * would restart the application for as long as the machine is on.
	 */
	@Test
	void neverAsksTwiceInTheSameChain() {
		Assertions.assertThat(UsnElevation.shouldRelaunch(true, WINDOWS, LAUNCHER, true, false)).isFalse();
	}

	/**
	 * No launcher means the IDE, the Maven build or the test suite - none of which
	 * can be restarted as themselves, and none of which should ever raise a prompt
	 * on a developer's machine.
	 */
	@Test
	void neverAsksFromABuild() {
		Assertions.assertThat(UsnElevation.shouldRelaunch(true, WINDOWS, null, false, false)).isFalse();
		Assertions.assertThat(UsnElevation.shouldRelaunch(true, WINDOWS, "  ", false, false)).isFalse();
	}

	/** The journal is a Windows thing; elsewhere the prompt would buy nothing. */
	@Test
	void neverAsksAwayFromWindows() {
		Assertions.assertThat(UsnElevation.shouldRelaunch(true, "Linux", LAUNCHER, false, false)).isFalse();
		Assertions.assertThat(UsnElevation.shouldRelaunch(true, null, LAUNCHER, false, false)).isFalse();
	}

	/** The way out for anyone who prefers no prompt to a faster catch-up. */
	@Test
	void neverAsksWhenTurnedOff() {
		Assertions.assertThat(UsnElevation.shouldRelaunch(false, WINDOWS, LAUNCHER, false, false)).isFalse();
	}

	/**
	 * Unset means on: tracking through the journal is what this product prefers, so
	 * the default is the one that gets it.
	 */
	@Test
	void treatsAnUnsetSettingAsOn() {
		Assertions.assertThat(UsnElevation.enabled(null)).isTrue();
		Assertions.assertThat(UsnElevation.enabled("  ")).isTrue();
		Assertions.assertThat(UsnElevation.enabled("true")).isTrue();
		Assertions.assertThat(UsnElevation.enabled("false")).isFalse();
	}

	@Test
	void recognisesTheMarkerAmongTheArgumentsItWasStartedWith() {
		Assertions.assertThat(UsnElevation.attempted(new String[] { "--spring.profiles.active=x" })).isFalse();
		Assertions.assertThat(UsnElevation.attempted(new String[0])).isFalse();
		Assertions.assertThat(UsnElevation.attempted(new String[] { "--other", UsnElevation.ATTEMPTED_ARGUMENT }))
				.isTrue();
	}
}