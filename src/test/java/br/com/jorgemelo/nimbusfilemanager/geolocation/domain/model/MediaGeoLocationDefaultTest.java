package br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * {@code manual} is a primitive {@code boolean}, so it defaults to {@code false}
 * both via the no-args constructor and the builder - no null third state,
 * matching the DB {@code DEFAULT FALSE}.
 */
class MediaGeoLocationDefaultTest {

	@Test
	void manualDefaultsToFalse() {
		Assertions.assertThat(new MediaGeoLocation().isManual()).isFalse();
		Assertions.assertThat(MediaGeoLocation.builder().build().isManual()).isFalse();
	}

	@Test
	void manualCanBeSetTrue() {
		Assertions.assertThat(MediaGeoLocation.builder().manual(true).build().isManual()).isTrue();
	}
}