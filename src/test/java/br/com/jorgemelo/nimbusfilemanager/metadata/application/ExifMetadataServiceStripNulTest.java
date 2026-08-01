package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ExifMetadataServiceStripNulTest {

	@Test
	void shouldRemoveNulCharactersSoPostgresTextColumnsAccept() {
		String withNul = "Canon" + (char) 0 + "EOS" + (char) 0;

		Assertions.assertThat(ExifMetadataService.stripNul(withNul)).isEqualTo("CanonEOS");
		Assertions.assertThat(ExifMetadataService.stripNul(withNul)).doesNotContain(String.valueOf((char) 0));
	}

	@Test
	void shouldReturnValueUnchangedWhenNoNulAndHandleNull() {
		Assertions.assertThat(ExifMetadataService.stripNul("clean value")).isEqualTo("clean value");
		Assertions.assertThat(ExifMetadataService.stripNul(null)).isNull();
	}
}