package br.com.jorgemelo.nimbusfilemanager.timeline.infrastructure.web;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.MediaLocationService;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.OfflineGeoDataset;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.shared.util.EnumUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.SecurityUtils;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.constants.TimelineConstants;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.enums.GeoPresence;

/**
 * Timeline page plus the dismissible "geographic dataset not configured"
 * notice. The notice tells users that media locations won't show until the
 * offline geo dataset is installed and the feature is enabled; it links admins
 * straight to the geo section of Settings. The dismissal is a per-user
 * preference (stored under the shared "layout" page key, like the
 * sidebar/theme), so dismissing the banner sticks until the user re-enables it
 * from Preferences.
 */
@Controller
public class TimelineWebController {

	private static final String ALL = "ALL";
	private static final String GEO_NOTICE_DISMISSED = "geo-notice-dismissed";
	private static final String SUBCATEGORIES_KEY = "subcategories";
	private static final Set<String> TIMELINE_TYPES = Set.of(ALL, "PHOTO", "VIDEO");

	/**
	 * What the panel may store. A whitelist because the request is a free-form map:
	 * without it, anything posted would become a preference row.
	 */
	private static final Set<String> FILTER_FIELDS = Set.of("from", "to", "manufacturer", "model", "minSizeMb",
			"maxSizeMb", "minDurationSeconds", "maxDurationSeconds", "minLongestSide", "geo");

	private final MediaLocationService mediaLocationService;
	private final OfflineGeoDataset offlineGeoDataset;
	private final UserPagePreferenceService userPagePreferenceService;

	public TimelineWebController(MediaLocationService mediaLocationService, OfflineGeoDataset offlineGeoDataset,
			UserPagePreferenceService userPagePreferenceService) {
		this.mediaLocationService = mediaLocationService;
		this.offlineGeoDataset = offlineGeoDataset;
		this.userPagePreferenceService = userPagePreferenceService;
	}

	@GetMapping("/app/timeline")
	public String timeline(Authentication authentication, Model model) {
		boolean geoConfigured = mediaLocationService.enabled() && offlineGeoDataset.status().available();
		boolean dismissed = geoNoticeDismissed(authentication);

		long pending = mediaLocationService.pendingCount();

		model.addAttribute("geoConfigured", geoConfigured);
		model.addAttribute("geoNoticeDismissed", dismissed);

		// Page-level banner: only when there is actually GPS media left to resolve.
		model.addAttribute("geoNoticeVisible", !geoConfigured && !dismissed && pending > 0);
		model.addAttribute("timelineType", savedTimelineType(authentication));
		model.addAttribute("subcategoryOptions", MediaSubcategory.values());
		model.addAttribute("selectedSubcategories", savedSubcategories(authentication));
		model.addAttribute("geoOptions", GeoPresence.values());

		// The panel reopens as the user left it, like every other screen preference.
		// Handed over as ready values rather than as a blob for the page to parse.
		model.addAttribute("savedFilters", savedFilters(authentication));

		return "app/timeline";
	}

	/**
	 * Persists the media-type filter the user picked, so the Timeline reopens on
	 * the same filter.
	 */
	@PostMapping("/app/timeline/type")
	@ResponseBody
	public Map<String, String> saveTimelineType(@RequestParam String type, Authentication authentication) {
		String value = TIMELINE_TYPES.contains(type) ? type : ALL;

		userPagePreferenceService.save(username(authentication), TimelineConstants.TIMELINE_PAGE_KEY,
				TimelineConstants.TYPE_KEY, value);

		return Map.of("type", value);
	}

	private String savedTimelineType(Authentication authentication) {
		String saved = userPagePreferenceService.find(username(authentication), TimelineConstants.TIMELINE_PAGE_KEY)
				.get(TimelineConstants.TYPE_KEY);

		return saved != null && TIMELINE_TYPES.contains(saved) ? saved : ALL;
	}

	/**
	 * Persists which media subcategories the Timeline shows (camera, WhatsApp,
	 * screenshot, …). An empty/absent selection means "show every subcategory", so
	 * the full set is stored - a blank value would be a no-op in the preference
	 * store and could never overwrite a previous filter.
	 */
	@PostMapping("/app/timeline/subcategories")
	@ResponseBody
	public Map<String, List<String>> saveTimelineSubcategories(
			@RequestParam(name = SUBCATEGORIES_KEY, required = false) List<MediaSubcategory> subcategories,
			Authentication authentication) {
		List<String> names = normalize(subcategories).stream().map(MediaSubcategory::name).toList();

		userPagePreferenceService.save(username(authentication), TimelineConstants.TIMELINE_PAGE_KEY, SUBCATEGORIES_KEY,
				String.join(",", names));

		return Map.of(SUBCATEGORIES_KEY, names);
	}

	private Set<String> savedSubcategories(Authentication authentication) {
		String saved = userPagePreferenceService.find(username(authentication), TimelineConstants.TIMELINE_PAGE_KEY)
				.get(SUBCATEGORIES_KEY);

		return normalize(parse(saved)).stream().map(MediaSubcategory::name)
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	/** Absent/empty selection widens to "all subcategories". */
	private Set<MediaSubcategory> normalize(Collection<MediaSubcategory> subcategories) {
		return subcategories == null || subcategories.isEmpty() ? EnumSet.allOf(MediaSubcategory.class)
				: EnumSet.copyOf(subcategories);
	}

	private List<MediaSubcategory> parse(String csv) {
		if (csv == null || csv.isBlank()) {
			return List.of();
		}

		return Arrays.stream(csv.split(",")).map(String::trim).filter(value -> !value.isEmpty())
				.map(name -> EnumUtils.valueOfOrNull(MediaSubcategory.class, name)).filter(Objects::nonNull).toList();
	}

	/**
	 * Stores the filter panel, one preference per control. An empty value removes
	 * the narrowing rather than storing an empty string, so that "cleared" and
	 * "never set" are the same state when the screen reopens.
	 */
	@PostMapping("/app/timeline/filters")
	@ResponseBody
	public Map<String, String> saveTimelineFilters(@RequestParam Map<String, String> filters,
			Authentication authentication) {
		Map<String, String> stored = new LinkedHashMap<>();

		filters.forEach((key, value) -> {
			if (!FILTER_FIELDS.contains(key)) {
				return;
			}

			String clean = value == null || value.isBlank() ? "" : value.trim();

			userPagePreferenceService.save(username(authentication), TimelineConstants.TIMELINE_PAGE_KEY,
					TimelineConstants.FILTER_KEY_PREFIX + key, clean);

			stored.put(key, clean);
		});

		return stored;
	}

	private Map<String, String> savedFilters(Authentication authentication) {
		Map<String, String> saved = userPagePreferenceService.find(username(authentication),
				TimelineConstants.TIMELINE_PAGE_KEY);

		Map<String, String> panel = new LinkedHashMap<>();

		FILTER_FIELDS.forEach(field -> {
			String value = saved.get(TimelineConstants.FILTER_KEY_PREFIX + field);

			panel.put(field, value == null ? "" : value);
		});

		return panel;
	}

	/** Called by timeline.js when the user clicks "não mostrar mais este aviso". */
	@PostMapping("/app/timeline/geo-notice/dismiss")
	@ResponseBody
	public Map<String, Boolean> dismissGeoNotice(Authentication authentication) {
		setGeoNoticeDismissed(authentication, true);

		return Map.of("dismissed", true);
	}

	/** Re-enables the notice from the Preferences tab. */
	@PostMapping("/app/timeline/geo-notice/restore")
	public String restoreGeoNotice(Authentication authentication) {
		setGeoNoticeDismissed(authentication, false);

		return "redirect:/app/settings/preferences";
	}

	private boolean geoNoticeDismissed(Authentication authentication) {
		return Boolean.parseBoolean(userPagePreferenceService
				.find(username(authentication), SharedConstants.LAYOUT_PAGE_KEY).get(GEO_NOTICE_DISMISSED));
	}

	private void setGeoNoticeDismissed(Authentication authentication, boolean dismissed) {
		userPagePreferenceService.save(username(authentication), SharedConstants.LAYOUT_PAGE_KEY, GEO_NOTICE_DISMISSED,
				Boolean.toString(dismissed));
	}

	private String username(Authentication authentication) {
		return SecurityUtils.usernameOr(authentication, null);
	}
}