package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import java.nio.file.Path;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class BoundarySourceTest {

	/**
	 * Per-territory fetching is optional: a source that does not implement it must
	 * yield nothing rather than fail, so the dataset update simply completes with
	 * the territories still missing instead of aborting.
	 */
	@Test
	void fetchMissingCountriesShouldDefaultToNothingForASourceThatDoesNotSupportIt() {
		BoundarySource source = mock(BoundarySource.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));

		Assertions.assertThat(source.fetchMissingCountries(List.of("ABW", "GIB"), Path.of("C:/workspace/geodata")))
				.isEmpty();
	}

	/**
	 * Publishing is optional too: a source that hands over files it did not acquire
	 * - an embedded dataset, a local folder - has nothing to swap in and nothing to
	 * roll back, so both calls are no-ops rather than something every
	 * implementation has to write.
	 */
	@Test
	void publishingAndDiscardingShouldDefaultToNoOpForASourceThatAcquiresNothing() {
		BoundarySource source = mock(BoundarySource.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));

		Path workspace = Path.of("C:/workspace/geodata");

		Assertions.assertThatNoException().isThrownBy(() -> source.commit(workspace));
		Assertions.assertThatNoException().isThrownBy(() -> source.discard(workspace));
	}
}