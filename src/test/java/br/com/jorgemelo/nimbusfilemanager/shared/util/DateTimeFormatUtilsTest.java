package br.com.jorgemelo.nimbusfilemanager.shared.util;

import java.time.LocalDateTime;
import java.time.Month;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class DateTimeFormatUtilsTest {

	@Test
	void humanShouldAlwaysRenderThreeMillisDigitsEvenWhenSubSecondIsZero() {
		Assertions.assertThat(DateTimeFormatUtils.human(LocalDateTime.of(2021, Month.MARCH, 25, 20, 40, 50)))
				.isEqualTo("25/03/2021 20:40:50.000");
	}

	@Test
	void humanShouldRenderThreeMillisDigitsWhenPresent() {
		Assertions.assertThat(DateTimeFormatUtils.human(LocalDateTime.of(2021, Month.MARCH, 25, 20, 40, 50, 7_000_000)))
				.isEqualTo("25/03/2021 20:40:50.007");
		Assertions
				.assertThat(DateTimeFormatUtils.human(LocalDateTime.of(2021, Month.MARCH, 25, 20, 41, 0, 500_000_000)))
				.isEqualTo("25/03/2021 20:41:00.500");
	}

	@Test
	void humanShouldRenderEmDashForNull() {
		Assertions.assertThat(DateTimeFormatUtils.absent()).isEqualTo("—");
	}
}