package br.com.jorgemelo.nimbusfilemanager.shared.util;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class TextUtilsTest {

	@Test
	void blankToNullShouldTrimAValueThatHasContent() {
		Assertions.assertThat(TextUtils.blankToNull("  camera  ")).isEqualTo("camera");
		Assertions.assertThat(TextUtils.blankToNull("camera")).isEqualTo("camera");
	}

	@Test
	void blankToNullShouldCollapseEveryBlankFormToNull() {
		Assertions.assertThat(TextUtils.blankToNull(null)).isNull();
		Assertions.assertThat(TextUtils.blankToNull("")).isNull();
		Assertions.assertThat(TextUtils.blankToNull("   ")).isNull();
		Assertions.assertThat(TextUtils.blankToNull("\t\n")).isNull();
	}

	@Test
	void upperBlankToNullShouldTrimAndUpperCaseAValueThatHasContent() {
		Assertions.assertThat(TextUtils.upperBlankToNull("  jpg  ")).isEqualTo("JPG");
		Assertions.assertThat(TextUtils.upperBlankToNull("JpG")).isEqualTo("JPG");
	}

	@Test
	void upperBlankToNullShouldCollapseEveryBlankFormToNull() {
		Assertions.assertThat(TextUtils.upperBlankToNull(null)).isNull();
		Assertions.assertThat(TextUtils.upperBlankToNull("")).isNull();
		Assertions.assertThat(TextUtils.upperBlankToNull("   ")).isNull();
	}
}