package br.com.jorgemelo.nimbusfilemanager.timeline.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.assertj.core.api.Assertions;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.ui.ExtendedModelMap;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.MediaLocationService;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.OfflineGeoDataset;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.OfflineGeoDatasetStatus;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;

/**
 * Timeline geo-notice logic: when the "configure geo" banner is visible, and
 * the dismiss/restore preference round-trip.
 */
class TimelineWebControllerTest {

	private final MediaLocationService mediaLocationService = mock(MediaLocationService.class);
	private final OfflineGeoDataset offlineGeoDataset = mock(OfflineGeoDataset.class);
	private final UserPagePreferenceService userPagePreferenceService = mock(UserPagePreferenceService.class);
	private final TimelineWebController controller = new TimelineWebController(mediaLocationService, offlineGeoDataset,
			userPagePreferenceService);

	private final Authentication auth = new TestingAuthenticationToken("bob", "x");

	private static OfflineGeoDatasetStatus available() {
		return new OfflineGeoDatasetStatus(true, "v1", 10, 0, null, null, "dir", null, "geoBoundaries", "ODbL");
	}

	@Test
	void showsNoticeWhenGeoNotConfiguredAndThereIsPendingGpsMedia() {
		when(mediaLocationService.enabled()).thenReturn(false);
		when(mediaLocationService.pendingCount()).thenReturn(5L);
		when(userPagePreferenceService.find("bob", "layout")).thenReturn(Map.of());

		ExtendedModelMap model = new ExtendedModelMap();

		Assertions.assertThat(controller.timeline(auth, model)).isEqualTo("app/timeline");

		Assertions.assertThat(model).containsEntry("geoNoticeVisible", true).containsEntry("geoConfigured", false)
				.containsEntry("geoNoticeDismissed", false);
	}

	@Test
	void hidesNoticeWhenGeoIsConfigured() {
		when(mediaLocationService.enabled()).thenReturn(true);
		when(offlineGeoDataset.status()).thenReturn(available());
		when(mediaLocationService.pendingCount()).thenReturn(5L);
		when(userPagePreferenceService.find("bob", "layout")).thenReturn(Map.of());

		ExtendedModelMap model = new ExtendedModelMap();

		controller.timeline(auth, model);

		Assertions.assertThat(model).containsEntry("geoConfigured", true).containsEntry("geoNoticeVisible", false);
	}

	@Test
	void hidesNoticeWhenDismissed() {
		when(mediaLocationService.enabled()).thenReturn(false);
		when(mediaLocationService.pendingCount()).thenReturn(5L);
		when(userPagePreferenceService.find("bob", "layout")).thenReturn(Map.of("geo-notice-dismissed", "true"));

		ExtendedModelMap model = new ExtendedModelMap();

		controller.timeline(auth, model);

		Assertions.assertThat(model).containsEntry("geoNoticeDismissed", true).containsEntry("geoNoticeVisible", false);
	}

	@Test
	void hidesNoticeWhenNoPendingMedia() {
		when(mediaLocationService.enabled()).thenReturn(false);
		when(mediaLocationService.pendingCount()).thenReturn(0L);
		when(userPagePreferenceService.find("bob", "layout")).thenReturn(Map.of());

		ExtendedModelMap model = new ExtendedModelMap();

		controller.timeline(auth, model);

		Assertions.assertThat(model).containsEntry("geoNoticeVisible", false);
	}

	@Test
	void dismissSavesPreferenceAndReturnsFlag() {
		Map<String, Boolean> result = controller.dismissGeoNotice(auth);

		Assertions.assertThat(result).containsEntry("dismissed", true);

		verify(userPagePreferenceService).save("bob", "layout", "geo-notice-dismissed", "true");
	}

	@Test
	void restoreResetsPreferenceAndRedirectsToPreferences() {
		String view = controller.restoreGeoNotice(auth);

		Assertions.assertThat(view).isEqualTo("redirect:/app/settings/preferences");

		verify(userPagePreferenceService).save("bob", "layout", "geo-notice-dismissed", "false");
	}

	@Test
	void savesTheSelectedTimelineType() {
		Map<String, String> result = controller.saveTimelineType("PHOTO", auth);

		Assertions.assertThat(result).containsEntry("type", "PHOTO");

		verify(userPagePreferenceService).save("bob", "timeline", "type", "PHOTO");
	}

	@Test
	void savingAnUnknownTimelineTypeFallsBackToAll() {
		Map<String, String> result = controller.saveTimelineType("bogus", auth);

		Assertions.assertThat(result).containsEntry("type", "ALL");

		verify(userPagePreferenceService).save("bob", "timeline", "type", "ALL");
	}

	@Test
	void exposesTheSavedTimelineType() {
		when(mediaLocationService.enabled()).thenReturn(false);
		when(mediaLocationService.pendingCount()).thenReturn(0L);
		when(userPagePreferenceService.find("bob", "layout")).thenReturn(Map.of());
		when(userPagePreferenceService.find("bob", "timeline")).thenReturn(Map.of("type", "VIDEO"));

		ExtendedModelMap model = new ExtendedModelMap();

		controller.timeline(auth, model);

		Assertions.assertThat(model).containsEntry("timelineType", "VIDEO");
	}

	@Test
	void savesTheSelectedSubcategories() {
		Map<String, List<String>> result = controller
				.saveTimelineSubcategories(List.of(MediaSubcategory.CAMERA, MediaSubcategory.WHATSAPP), auth);

		Assertions.assertThat(result.get("subcategories")).containsExactly("CAMERA", "WHATSAPP");

		verify(userPagePreferenceService).save("bob", "timeline", "subcategories", "CAMERA,WHATSAPP");
	}

	@Test
	void savingNoSubcategoriesStoresEveryOneSoTheFilterCanBeCleared() {
		String everySubcategory = Arrays.stream(MediaSubcategory.values()).map(MediaSubcategory::name)
				.collect(Collectors.joining(","));

		controller.saveTimelineSubcategories(null, auth);

		verify(userPagePreferenceService).save("bob", "timeline", "subcategories", everySubcategory);
	}

	@Test
	void exposesSavedSubcategoriesAndTheFullOptionList() {
		when(mediaLocationService.enabled()).thenReturn(false);
		when(mediaLocationService.pendingCount()).thenReturn(0L);
		when(userPagePreferenceService.find("bob", "layout")).thenReturn(Map.of());
		when(userPagePreferenceService.find("bob", "timeline")).thenReturn(Map.of("subcategories", "CAMERA,WHATSAPP"));

		ExtendedModelMap model = new ExtendedModelMap();

		controller.timeline(auth, model);

		Assertions.assertThat(model.getAttribute("selectedSubcategories"))
				.asInstanceOf(InstanceOfAssertFactories.iterable(String.class))
				.containsExactlyInAnyOrder("CAMERA", "WHATSAPP");
		Assertions.assertThat((MediaSubcategory[]) model.getAttribute("subcategoryOptions"))
				.containsExactly(MediaSubcategory.values());
	}
	/**
	 * The panel is a screen preference like any other: the rule is that what the
	 * user picked is reapplied when the screen reopens, never reset to the default.
	 */
	@Test
	void storesEachFilterControlOfThePanel() {
		controller.saveTimelineFilters(Map.of("capturedFrom", "2008-01-01", "manufacturer", " Canon ", "geo",
				"WITHOUT_LOCATION"), auth);

		verify(userPagePreferenceService).save("bob", "timeline", "filter.capturedFrom", "2008-01-01");
		verify(userPagePreferenceService).save("bob", "timeline", "filter.manufacturer", "Canon");
		verify(userPagePreferenceService).save("bob", "timeline", "filter.geo", "WITHOUT_LOCATION");
	}

	/**
	 * The request is a free-form map, so anything not on the whitelist would
	 * otherwise become a preference row of its own.
	 */
	@Test
	void ignoresAnythingThatIsNotAFilterControl() {
		Map<String, String> stored = controller.saveTimelineFilters(Map.of("dropTable", "yes"), auth);

		Assertions.assertThat(stored).isEmpty();

		verify(userPagePreferenceService, never()).save(any(), any(), eq("filter.dropTable"), any());
	}

	@Test
	void reopensTheScreenWithThePanelTheUserLeft() {
		when(mediaLocationService.enabled()).thenReturn(false);
		when(mediaLocationService.pendingCount()).thenReturn(0L);
		when(userPagePreferenceService.find("bob", "layout")).thenReturn(Map.of());
		when(userPagePreferenceService.find("bob", "timeline"))
				.thenReturn(Map.of("filter.minSizeMb", "50", "filter.geo", "WITH_LOCATION"));

		ExtendedModelMap model = new ExtendedModelMap();

		controller.timeline(auth, model);

		@SuppressWarnings("unchecked")
		Map<String, String> saved = (Map<String, String>) model.get("savedFilters");

		Assertions.assertThat(saved).containsEntry("minSizeMb", "50").containsEntry("geo", "WITH_LOCATION")
				.containsEntry("model", "");
	}

	/**
	 * Clearing a filter has to survive leaving the screen. It did not: the
	 * preference store ignores a blank value, so storing "" left the previous one
	 * in place and the panel came back filled - "Limpar filtros" looked like it had
	 * worked until the user navigated away and returned.
	 */
	@Test
	void clearingAFilterRemovesItInsteadOfStoringAnEmptyValue() {
		Map<String, String> campos = new LinkedHashMap<>();
		campos.put("geo", "");
		campos.put("manufacturer", "  ");
		campos.put("minSizeMb", "50");

		controller.saveTimelineFilters(campos, auth);

		verify(userPagePreferenceService).remove("bob", "timeline", "filter.geo");
		verify(userPagePreferenceService).remove("bob", "timeline", "filter.manufacturer");
		verify(userPagePreferenceService).save("bob", "timeline", "filter.minSizeMb", "50");

		verify(userPagePreferenceService, never()).save(any(), any(), eq("filter.geo"), any());
	}

}