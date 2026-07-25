package br.com.jorgemelo.nimbusfilemanager.media.domain.enums;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The file explorer toolbar renders straight from these constants, so the wire
 * value, the label and the lenient lookup are contract, not incidental.
 */
class FileSortOptionTest {

	@Test
	void everyOptionShouldExposeItsWireValueAndLabel() {
		Assertions.assertThat(FileSortOption.NAME.value()).isEqualTo("name");
		Assertions.assertThat(FileSortOption.NAME.label()).isEqualTo("Nome (A-Z)");
		Assertions.assertThat(FileSortOption.DATE_NEWEST.value()).isEqualTo("date-newest");
		Assertions.assertThat(FileSortOption.DATE_NEWEST.label()).isEqualTo("Data (mais recente)");
	}

	@Test
	void fromValueShouldResolveEveryOfferedValue() {
		for (FileSortOption option : FileSortOption.values()) {
			Assertions.assertThat(FileSortOption.fromValue(option.value())).isEqualTo(option);
		}
	}

	/**
	 * An unknown or absent value arrives from the query string, so it falls back to
	 * the default order instead of failing the page.
	 */
	@Test
	void fromValueShouldFallBackToNameForAnythingUnknown() {
		Assertions.assertThat(FileSortOption.fromValue("size-desc")).isEqualTo(FileSortOption.NAME);
		Assertions.assertThat(FileSortOption.fromValue("")).isEqualTo(FileSortOption.NAME);
		Assertions.assertThat(FileSortOption.fromValue(null)).isEqualTo(FileSortOption.NAME);
	}
}