package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

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
		Assertions.assertThat(UsnElevation.shouldRelaunch(true, WINDOWS, LAUNCHER, false, () -> false)).isTrue();
	}

	/** Already administrator: the volume opens, so there is nothing to ask for. */
	@Test
	void staysQuietWhenTheVolumeAlreadyOpens() {
		Assertions.assertThat(UsnElevation.shouldRelaunch(true, WINDOWS, LAUNCHER, false, () -> true)).isFalse();
	}

	/**
	 * The marker the restart carries. Without it, a volume that stays unreadable
	 * even to an administrator - a network drive, a filesystem with no journal -
	 * would restart the application for as long as the machine is on.
	 */
	@Test
	void neverAsksTwiceInTheSameChain() {
		Assertions.assertThat(UsnElevation.shouldRelaunch(true, WINDOWS, LAUNCHER, true, () -> false)).isFalse();
	}

	/**
	 * No launcher means the IDE, the Maven build or the test suite - none of which
	 * can be restarted as themselves, and none of which should ever raise a prompt
	 * on a developer's machine.
	 */
	@Test
	void neverAsksFromABuild() {
		Assertions.assertThat(UsnElevation.shouldRelaunch(true, WINDOWS, null, false, () -> false)).isFalse();
		Assertions.assertThat(UsnElevation.shouldRelaunch(true, WINDOWS, "  ", false, () -> false)).isFalse();
	}

	/** The journal is a Windows thing; elsewhere the prompt would buy nothing. */
	@Test
	void neverAsksAwayFromWindows() {
		Assertions.assertThat(UsnElevation.shouldRelaunch(true, "Linux", LAUNCHER, false, () -> false)).isFalse();
		Assertions.assertThat(UsnElevation.shouldRelaunch(true, null, LAUNCHER, false, () -> false)).isFalse();
	}

	/**
	 * The volume question is asked last, and only once every other condition
	 * already holds - which is what keeps it off the platforms that have no
	 * volume to open. Answering it reaches into kernel32, and merely loading that
	 * library away from Windows throws an {@code ExceptionInInitializerError}: an
	 * {@code Error}, so the caller's own {@code catch} does not hold it either. It
	 * used to be a {@code boolean} parameter, and the call therefore sat in the
	 * caller's argument list, where Java runs it before this method can rule
	 * anything out - so every Linux and macOS start died in {@code main}, before
	 * Spring, and only the one test that boots a real JVM could see it.
	 */
	@Test
	void asksTheVolumeQuestionOnlyWhenEverythingElseAlreadyHolds() {
		AtomicBoolean asked = new AtomicBoolean();

		BooleanSupplier volume = () -> {
			asked.set(true);

			return false;
		};

		Assertions.assertThat(UsnElevation.shouldRelaunch(true, "Linux", LAUNCHER, false, volume)).isFalse();
		Assertions.assertThat(UsnElevation.shouldRelaunch(true, "Mac OS X", LAUNCHER, false, volume)).isFalse();
		Assertions.assertThat(UsnElevation.shouldRelaunch(false, WINDOWS, LAUNCHER, false, volume)).isFalse();
		Assertions.assertThat(UsnElevation.shouldRelaunch(true, WINDOWS, null, false, volume)).isFalse();
		Assertions.assertThat(UsnElevation.shouldRelaunch(true, WINDOWS, LAUNCHER, true, volume)).isFalse();

		Assertions.assertThat(asked).as("a start that was never going to relaunch opened a volume anyway").isFalse();

		Assertions.assertThat(UsnElevation.shouldRelaunch(true, WINDOWS, LAUNCHER, false, volume)).isTrue();

		Assertions.assertThat(asked).as("and the one start that could relaunch has to ask").isTrue();
	}

	/** The way out for anyone who prefers no prompt to a faster catch-up. */
	@Test
	void neverAsksWhenTurnedOff() {
		Assertions.assertThat(UsnElevation.shouldRelaunch(false, WINDOWS, LAUNCHER, false, () -> false)).isFalse();
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