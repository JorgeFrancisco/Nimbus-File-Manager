package br.com.jorgemelo.nimbusfilemanager.organization.application.resolver;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;

class OrganizationLayoutResolverTest {

	private final OrganizationLayoutResolver resolver = new OrganizationLayoutResolver();

	@Test
	void shouldNormalizeDefaultLayouts() {
		Assertions.assertThat(resolver.normalize(null)).isEqualTo("YEAR_MONTH/DAY/SUBCATEGORY/FILE_TYPE");
		Assertions.assertThat(resolver.normalize(OrganizationLayout.DEFAULT))
				.isEqualTo("YEAR_MONTH/DAY/SUBCATEGORY/FILE_TYPE");
		Assertions.assertThat(resolver.normalize(OrganizationLayout.YEAR_MONTH_DAY_SUBCATEGORY_FILE_TYPE))
				.isEqualTo("YEAR_MONTH/DAY/SUBCATEGORY/FILE_TYPE");
	}

	@Test
	void shouldNormalizeEachAdditionalLayout() {
		Assertions.assertThat(resolver.normalize(OrganizationLayout.YEAR_MONTH_DAY)).isEqualTo("YEAR_MONTH/DAY");
		Assertions.assertThat(resolver.normalize(OrganizationLayout.YEAR_MONTH_SUBCATEGORY_FILE_TYPE))
				.isEqualTo("YEAR_MONTH/SUBCATEGORY/FILE_TYPE");
		Assertions.assertThat(resolver.normalize(OrganizationLayout.SUBCATEGORY_YEAR_MONTH_DAY))
				.isEqualTo("SUBCATEGORY/YEAR_MONTH/DAY");
	}

	@Test
	void shouldResolveFolderUsingDefaultLayout() {
		Path folder = resolver.resolveFolder(Path.of("target"), null, "202405", "09", "CAMERA", "IMAGENS");

		Assertions.assertThat(folder).isEqualTo(Path.of("target", "202405", "09", "CAMERA", "IMAGENS"));
	}

	/**
	 * The location subdivides the chosen structure: its segments go in right after
	 * the date ones, and a blank or absent segment must not create a folder with no
	 * name - which is what the user would get for a photo whose city is unknown.
	 */
	@Test
	void shouldInsertLocationSegmentsAndSkipTheBlankOnes() {
		Path folder = resolver.resolveFolder(Path.of("target"), "YEAR_MONTH/DAY", "202405", "09", "CAMERA", "IMAGENS",
				Arrays.asList("Brasil", "  ", null, "Rio de Janeiro"));

		Assertions.assertThat(folder).isEqualTo(Path.of("target", "202405", "09", "Brasil", "Rio de Janeiro"));
	}

	/** No location at all leaves the layout exactly as it was. */
	@Test
	void shouldLeaveTheLayoutUntouchedWithoutLocationSegments() {
		Path withNull = resolver.resolveFolder(Path.of("target"), "YEAR_MONTH/DAY", "202405", "09", "CAMERA",
				"IMAGENS", null);
		Path withEmpty = resolver.resolveFolder(Path.of("target"), "YEAR_MONTH/DAY", "202405", "09", "CAMERA",
				"IMAGENS", List.of());

		Assertions.assertThat(withNull).isEqualTo(Path.of("target", "202405", "09"));
		Assertions.assertThat(withEmpty).isEqualTo(withNull);
	}

	@Test
	void shouldResolveFolderUsingYearMonthDayLayout() {
		Path folder = resolver.resolveFolder(Path.of("target"), "YEAR_MONTH/DAY", "202405", "09", "CAMERA", "IMAGENS");

		Assertions.assertThat(folder).isEqualTo(Path.of("target", "202405", "09"));
	}

	@Test
	void shouldResolveFolderUsingYearMonthSubcategoryFileTypeLayout() {
		Path folder = resolver.resolveFolder(Path.of("target"), "YEAR_MONTH/SUBCATEGORY/FILE_TYPE", "202405", "09",
				"CAMERA", "IMAGENS");

		Assertions.assertThat(folder).isEqualTo(Path.of("target", "202405", "CAMERA", "IMAGENS"));
	}

	@Test
	void shouldResolveFolderUsingSubcategoryYearMonthDayLayout() {
		Path folder = resolver.resolveFolder(Path.of("target"), "SUBCATEGORY/YEAR_MONTH/DAY", "202405", "09", "CAMERA",
				"IMAGENS");

		Assertions.assertThat(folder).isEqualTo(Path.of("target", "CAMERA", "202405", "09"));
	}

	@Test
	void shouldNormalizeFlatLayout() {
		Assertions.assertThat(resolver.normalize(OrganizationLayout.FLAT)).isEqualTo("FLAT");
	}

	@Test
	void shouldResolveFlatLayoutStraightToTheTargetFolderIgnoringDateAndLocation() {
		Path folder = resolver.resolveFolder(Path.of("target"), "FLAT", "202405", "09", "CAMERA", "IMAGENS",
				List.of("Brasil", "Parana"));

		Assertions.assertThat(folder).isEqualTo(Path.of("target"));
	}

	@Test
	void flatShouldBeTheLastLayoutOption() {
		OrganizationLayout[] values = OrganizationLayout.values();

		Assertions.assertThat(values[values.length - 1]).isEqualTo(OrganizationLayout.FLAT);
	}

	/** The example path is what the dropdown shows beside each layout name. */
	@Test
	void shouldExposeTheExamplePathOfEachLayout() {
		Assertions.assertThat(OrganizationLayout.YEAR_MONTH_DAY.example()).isEqualTo("2026-07/10");
	}

	@Test
	void shouldRejectUnsupportedLayout() {
		assertThatIllegalArgumentException()
				.isThrownBy(
						() -> resolver.resolveFolder(Path.of("target"), "UNKNOWN", "202405", "09", "CAMERA", "IMAGENS"))
				.withMessageContaining("Unsupported organization layout");
	}
}