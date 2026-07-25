package br.com.jorgemelo.nimbusfilemanager.shared.util;

import java.time.Month;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class EnumUtilsTest {

	@Test
	void valueOfOrDefaultShouldResolveAnExactConstantName() {
		Assertions.assertThat(EnumUtils.valueOfOrDefault(Month.class, "MARCH", Month.JANUARY)).isEqualTo(Month.MARCH);
	}

	@Test
	void valueOfOrDefaultShouldFallBackForNullOrBlank() {
		Assertions.assertThat(EnumUtils.valueOfOrDefault(Month.class, null, Month.JANUARY)).isEqualTo(Month.JANUARY);
		Assertions.assertThat(EnumUtils.valueOfOrDefault(Month.class, "", Month.JANUARY)).isEqualTo(Month.JANUARY);
		Assertions.assertThat(EnumUtils.valueOfOrDefault(Month.class, "   ", Month.JANUARY)).isEqualTo(Month.JANUARY);
	}

	@Test
	void valueOfOrDefaultShouldFallBackForAnUnrecognizedValueInsteadOfThrowing() {
		Assertions.assertThat(EnumUtils.valueOfOrDefault(Month.class, "SMARCH", Month.JANUARY))
				.isEqualTo(Month.JANUARY);
	}

	/**
	 * Matching is deliberately as strict as {@link Enum#valueOf}: no trimming and
	 * no case folding, so a padded or lower-case value resolves to the fallback.
	 */
	@Test
	void valueOfOrDefaultShouldNotTrimOrCaseFoldTheValue() {
		Assertions.assertThat(EnumUtils.valueOfOrDefault(Month.class, "march", Month.JANUARY)).isEqualTo(Month.JANUARY);
		Assertions.assertThat(EnumUtils.valueOfOrDefault(Month.class, " MARCH ", Month.JANUARY))
				.isEqualTo(Month.JANUARY);
	}

	@Test
	void valueOfOrNullShouldResolveOrYieldNull() {
		Assertions.assertThat(EnumUtils.valueOfOrNull(Month.class, "APRIL")).isEqualTo(Month.APRIL);
		Assertions.assertThat(EnumUtils.valueOfOrNull(Month.class, "SMARCH")).isNull();
		Assertions.assertThat(EnumUtils.valueOfOrNull(Month.class, null)).isNull();
	}
}