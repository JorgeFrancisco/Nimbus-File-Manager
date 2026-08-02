package br.com.jorgemelo.nimbusfilemanager.shared.application;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * When background work should stand down, and when the failure it just caught
 * is explained by the state the application is in rather than by a defect.
 *
 * <p>
 * Both states were found by running the packaged application: a restore printed
 * {@code relation "execution" does not exist} from the folder watcher, and a
 * clean Ctrl+C printed an interrupted download and a query against a closed
 * pool. Three stack traces, no defects.
 */
class BackgroundWorkGateTest {

	private final BackgroundWorkGate gate = new BackgroundWorkGate();

	@Test
	void letsWorkThroughWhileNothingIsHappeningToTheDatabase() {
		Assertions.assertThat(gate.standDown()).isFalse();
		Assertions.assertThat(gate.restoring()).isFalse();
	}

	/** The window a restore owns: every table can be momentarily absent. */
	@Test
	void standsWorkDownWhileTheCatalogIsBeingReplaced() {
		gate.restoreStarted();

		Assertions.assertThat(gate.standDown()).isTrue();
		Assertions.assertThat(gate.restoring()).isTrue();

		gate.restoreFinished();

		Assertions.assertThat(gate.standDown()).isFalse();
	}

	/**
	 * Closing is one-way: nothing reopens, so the flag never has to be cleared -
	 * and a task that wakes late still finds the right answer.
	 */
	@Test
	void standsWorkDownOnceTheApplicationStartsClosing() {
		gate.onContextClosed(null);

		Assertions.assertThat(gate.standDown()).isTrue();
	}

	/**
	 * A shutdown during a restore leaves both set; the screens still have to be
	 * told it is a restore, because that is what they are waiting on.
	 */
	@Test
	void tellsARestoreApartFromAShutdown() {
		gate.onContextClosed(null);

		Assertions.assertThat(gate.restoring()).isFalse();

		gate.restoreStarted();

		Assertions.assertThat(gate.restoring()).isTrue();
	}
}