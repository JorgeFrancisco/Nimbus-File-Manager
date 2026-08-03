package br.com.jorgemelo.nimbusfilemanager.shared.application;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The suite runs from class files rather than from the packaged jar, so there is
 * no manifest to read - which is the same situation as a run from the IDE, and
 * the one every caller has to survive. Asserting it here is what keeps a future
 * "just return unknown" from turning an absent version into one that could be
 * compared.
 */
class InstalledVersionTest {

	@Test
	void hasNoVersionOutsideAPackagedBuild() {
		Assertions.assertThat(InstalledVersion.current()).isEmpty();
	}
}