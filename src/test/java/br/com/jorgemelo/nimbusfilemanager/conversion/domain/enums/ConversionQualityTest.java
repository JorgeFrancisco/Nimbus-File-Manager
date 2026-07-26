package br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The quality vocabulary offered on screen, and what each profile means in
 * encoder terms.
 */
class ConversionQualityTest {

	/**
	 * A GPU that refuses a file must not change the quality the user asked for: the
	 * software profile of the same level takes over, never a different one.
	 */
	@Test
	void everyHardwareProfileFallsBackToTheSoftwareProfileOfItsOwnLevel() {
		Assertions.assertThat(ConversionQuality.FAST_HIGH_QUALITY.softwareEquivalent())
				.isEqualTo(ConversionQuality.HIGH_QUALITY);
		Assertions.assertThat(ConversionQuality.FAST_BALANCED.softwareEquivalent())
				.isEqualTo(ConversionQuality.BALANCED);
	}

	/** A software profile is already its own fallback. */
	@Test
	void softwareProfilesAreTheirOwnEquivalent() {
		Assertions.assertThat(ConversionQuality.HIGH_QUALITY.softwareEquivalent())
				.isEqualTo(ConversionQuality.HIGH_QUALITY);
		Assertions.assertThat(ConversionQuality.BALANCED.softwareEquivalent()).isEqualTo(ConversionQuality.BALANCED);
	}

	/**
	 * The hardware numbers are calibrated against the software ones on real footage,
	 * not copied from them: a GPU encoder needs a lower number, and spends more
	 * bits, to hold the quality its software counterpart reaches.
	 */
	@Test
	void hardwareProfilesAreMarkedAsSuchAndOrderedByQuality() {
		Assertions.assertThat(ConversionQuality.FAST_HIGH_QUALITY.requiresHardware()).isTrue();
		Assertions.assertThat(ConversionQuality.FAST_BALANCED.requiresHardware()).isTrue();
		Assertions.assertThat(ConversionQuality.BALANCED.requiresHardware()).isFalse();

		Assertions.assertThat(ConversionQuality.FAST_HIGH_QUALITY.quality())
				.isLessThan(ConversionQuality.FAST_BALANCED.quality());
	}
}