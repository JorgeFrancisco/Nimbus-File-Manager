package br.com.jorgemelo.nimbusfilemanager.timeline.application;

import java.time.LocalDate;
import java.time.Month;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineFilterForm;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.enums.GeoPresence;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.TimelineFilter;

/**
 * The translation between what a screen can express and what the queries take.
 *
 * <p>
 * It is worth its own test because the unit conversion lives here: the panel
 * asks for megabytes because that is what a person thinks in, the catalog
 * stores bytes, and a filter that got that wrong by a factor of 1024 would
 * still return results - just the wrong ones, silently.
 */
class TimelineFiltersTest {

	@Test
	void convertsMegabytesToBytesBecauseTheCatalogStoresBytes() {
		TimelineFilterForm form = new TimelineFilterForm(null, null, null, null, 5L, 1024L, null, null, null, null);

		TimelineFilter filter = TimelineFilters.from(form);

		Assertions.assertThat(filter.scale().minBytes()).isEqualTo(5L * 1024 * 1024);
		Assertions.assertThat(filter.scale().maxBytes()).isEqualTo(1024L * 1024 * 1024);
	}

	/**
	 * An absent panel and an empty panel have to mean the same thing, because the
	 * first is a caller that offers no filters and the second is a user who
	 * cleared them - and both expect the timeline they had before.
	 */
	@Test
	void treatsAnAbsentOrEmptyPanelAsNarrowingNothing() {
		TimelineFilter fromNull = TimelineFilters.from(null);

		TimelineFilter fromEmpty = TimelineFilters
				.from(new TimelineFilterForm(null, null, null, null, null, null, null, null, null, null));

		Assertions.assertThat(fromNull).isEqualTo(TimelineFilter.NONE);
		Assertions.assertThat(fromEmpty).isEqualTo(TimelineFilter.NONE);
		Assertions.assertThat(fromEmpty.isNarrowing()).isFalse();
	}

	@Test
	void carriesTheWindowTheCameraAndThePresenceOfALocation() {
		TimelineFilterForm form = new TimelineFilterForm(LocalDate.of(2008, Month.JANUARY, 1),
				LocalDate.of(2008, Month.DECEMBER, 31), "Canon", "EOS 5D", null, null, 30.0, 600.0, 1920,
				GeoPresence.WITHOUT_LOCATION);

		TimelineFilter filter = TimelineFilters.from(form);

		Assertions.assertThat(filter.window().from()).isEqualTo(LocalDate.of(2008, Month.JANUARY, 1));
		Assertions.assertThat(filter.window().to()).isEqualTo(LocalDate.of(2008, Month.DECEMBER, 31));
		Assertions.assertThat(filter.camera().manufacturer()).isEqualTo("Canon");
		Assertions.assertThat(filter.camera().model()).isEqualTo("EOS 5D");
		Assertions.assertThat(filter.scale().minDurationSeconds()).isEqualTo(30.0);
		Assertions.assertThat(filter.scale().minLongestSide()).isEqualTo(1920);
		Assertions.assertThat(filter.geo()).isEqualTo(GeoPresence.WITHOUT_LOCATION);
		Assertions.assertThat(filter.isNarrowing()).isTrue();
	}
	/**
	 * Each control on its own has to be enough to count as narrowing, because the
	 * badge on the panel is what tells a filtered timeline apart from an empty
	 * library - and getting that wrong in one dimension is invisible until
	 * somebody filters by exactly that one.
	 */
	@Test
	void countsAsNarrowingWhateverTheSingleControlThatWasUsed() {
		Assertions.assertThat(only(new TimelineFilterForm(LocalDate.of(2008, Month.JANUARY, 1), null, null, null,
				null, null, null, null, null, null))).isTrue();
		Assertions.assertThat(only(new TimelineFilterForm(null, LocalDate.of(2008, Month.JANUARY, 1), null, null, null, null, null,
				null, null, null))).isTrue();
		Assertions.assertThat(only(new TimelineFilterForm(null, null, "Canon", null, null, null, null, null, null,
				null))).isTrue();
		Assertions.assertThat(only(new TimelineFilterForm(null, null, null, "EOS", null, null, null, null, null,
				null))).isTrue();
		Assertions.assertThat(only(new TimelineFilterForm(null, null, null, null, 10L, null, null, null, null,
				null))).isTrue();
		Assertions.assertThat(only(new TimelineFilterForm(null, null, null, null, null, null, 5.0, null, null,
				null))).isTrue();
		Assertions.assertThat(only(new TimelineFilterForm(null, null, null, null, null, null, null, null, 1920,
				null))).isTrue();
		Assertions.assertThat(only(new TimelineFilterForm(null, null, null, null, null, null, null, null, null,
				GeoPresence.WITH_LOCATION))).isTrue();
	}

	/** "Either way" is the absence of a geo filter, not a filter for either. */
	@Test
	void doesNotCountTheGeoOptionThatMeansEitherWay() {
		Assertions.assertThat(only(
				new TimelineFilterForm(null, null, null, null, null, null, null, null, null, GeoPresence.ANY)))
				.isFalse();
	}

	private boolean only(TimelineFilterForm form) {
		return TimelineFilters.from(form).isNarrowing();
	}

}