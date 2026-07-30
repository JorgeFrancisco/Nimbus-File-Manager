package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A boundary feature carries its ISO code as an ordinary GeoJSON property, so
 * it may be missing or malformed: the codes are then unknown, never guessed.
 */
class CountryCodesTest {

	@Test
	void translatesAKnownAlphaThreeCodeAndItsCountryName() {
		assertThat(CountryCodes.alpha2(" bra ")).isEqualTo("BR");
		assertThat(CountryCodes.displayName("br")).isEqualTo("Brasil");
	}

	@Test
	void aFeatureWithoutAnIsoCodeResolvesToNothing() {
		assertThat(CountryCodes.alpha2(null)).isNull();
		assertThat(CountryCodes.alpha2("XXX")).isNull();
		assertThat(CountryCodes.displayName(null)).isNull();
		assertThat(CountryCodes.displayName("BRA")).isNull();
	}
}