package br.com.jorgemelo.nimbusfilemanager.metadata.application.date;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Month;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.model.MetadataResult;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;

class MediaDateResolverTest {

	@Test
	void resolveShouldKeepDateSourceOnlyWhenDateIsValid() {
		MediaDateResolver resolver = new MediaDateResolver(new CaptureDateValidator(Clock.systemDefaultZone()));

		var valid = resolver.resolve(MetadataResult.builder().captureDate(LocalDateTime.of(2024, Month.MAY, 9, 10, 30))
				.dateSource(DateSource.EXIF).build());
		var invalid = resolver.resolve(MetadataResult.builder().captureDate(LocalDateTime.of(1990, Month.JANUARY, 1, 0,
				0))
				.dateSource(DateSource.EXIF).build());

		Assertions.assertThat(valid.captureDate()).isEqualTo(LocalDateTime.of(2024, Month.MAY, 9, 10, 30));
		Assertions.assertThat(valid.dateSource()).isEqualTo(DateSource.EXIF);
		Assertions.assertThat(invalid.captureDate()).isNull();
		Assertions.assertThat(invalid.dateSource()).isNull();
	}
}