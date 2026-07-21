package br.com.jorgemelo.nimbusfilemanager.metadata.application.date;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Month;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class FolderLayoutDateResolverTest {

	private final FolderLayoutDateResolver resolver = new FolderLayoutDateResolver(Clock.systemDefaultZone());

	@Test
	void shouldResolveDateFromKnownOrganizationLayout() {
		Path file = Path.of("C:/organized/202405/09/CAMERA/IMAGENS/photo.jpg");

		Assertions.assertThat(resolver.resolve(file)).isEqualTo(LocalDateTime.of(2024, Month.MAY, 9, 0, 0));
	}

	@Test
	void shouldRejectUnknownFoldersAndInvalidDates() {
		Assertions.assertThat(resolver.resolve(Path.of("C:/organized/202405/09/UNKNOWN/IMAGENS/photo.jpg"))).isNull();
		Assertions.assertThat(resolver.resolve(Path.of("C:/organized/202413/09/CAMERA/IMAGENS/photo.jpg"))).isNull();
		Assertions.assertThat(resolver.resolve(Path.of("C:/organized/189912/09/CAMERA/IMAGENS/photo.jpg"))).isNull();
		Assertions.assertThat(resolver.resolve(Path.of("photo.jpg"))).isNull();
		Assertions.assertThat(resolver.resolve(null)).isNull();
	}

	@Test
	void shouldRejectUnknownFileTypeMalformedDatePartsAndOutOfRangeYears() {
		// Known subcategory but an unknown file-type folder.
		Assertions.assertThat(resolver.resolve(Path.of("C:/o/202405/09/CAMERA/HOLOGRAMS/photo.jpg"))).isNull();
		// Year-month is not exactly 6 digits.
		Assertions.assertThat(resolver.resolve(Path.of("C:/o/2024/09/CAMERA/IMAGENS/photo.jpg"))).isNull();
		// Day is not exactly 2 digits.
		Assertions.assertThat(resolver.resolve(Path.of("C:/o/202405/9/CAMERA/IMAGENS/photo.jpg"))).isNull();
		// Well-formed but absurdly far in the future (beyond currentYear + 1).
		Assertions.assertThat(resolver.resolve(Path.of("C:/o/999912/09/CAMERA/IMAGENS/photo.jpg"))).isNull();
		// Enough folders for a subcategory but not for the day / year-month levels.
		Assertions.assertThat(resolver.resolve(Path.of("CAMERA/IMAGENS/photo.jpg"))).isNull();
	}

	@Test
	void shouldRejectWhenTheLayoutDepthIsIncomplete() {
		// Only a file-type folder above the file: no subcategory level.
		Assertions.assertThat(resolver.resolve(Path.of("IMAGENS/photo.jpg"))).isNull();
		// Day present but no year-month level above it.
		Assertions.assertThat(resolver.resolve(Path.of("09/CAMERA/IMAGENS/photo.jpg"))).isNull();
		// Year-month level lands on the filesystem root, whose path has no name element.
		Assertions.assertThat(resolver.resolve(Path.of("/09/CAMERA/IMAGENS/photo.jpg"))).isNull();
	}
}