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
}