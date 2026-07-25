(function () {
	"use strict";
	var i18n = window.NimbusFileManagerI18n, t = i18n.t;
	var monthFormatter = new Intl.DateTimeFormat(i18n.locale, { month: "long" });
	var weekdayFormatter = new Intl.DateTimeFormat(i18n.locale, { weekday: "long" });
	var monthYearFormatter = new Intl.DateTimeFormat(i18n.locale, { month: "long", year: "numeric" });
	var state = { type: "ALL", cursor: null, hasMore: false, loading: false, prefetch: null, controller: null,
		currentYear: null, currentMonth: null, items: [], from: null };
	var yearHighlightScheduled = false;

	function element(tag, className, text) {
		var node = document.createElement(tag); if (className) node.className = className;
		if (text !== undefined) node.textContent = text; return node;
	}
	function formatDuration(seconds) {
		if (seconds == null) return t("js.timeline.video"); var value = Math.max(0, Math.round(seconds));
		return Math.floor(value / 60) + ":" + String(value % 60).padStart(2, "0");
	}
	function renderItem(item) {
		state.items.push(item);
		var previewClass = item.type === "VIDEO" ? "js-lightbox-video" : "js-lightbox-image";
		var card = element("button", "timeline-card " + previewClass); card.type = "button";
		card.dataset.publicId = item.id; card.dataset.name = item.fileName; card.dataset.mediaType = item.type;
		card.setAttribute("aria-label", item.fileName);
		var image = element("img", "timeline-thumbnail"); image.src = item.thumbnailUrl; image.alt = "";
		image.loading = "lazy"; image.decoding = "async"; image.width = item.width || 320; image.height = item.height || 240;
		image.addEventListener("error", function () { card.classList.add("thumbnail-error"); }); card.appendChild(image);
		if (item.type === "VIDEO") {
			var badge = element("span", "timeline-video-badge");
			badge.innerHTML = '<i class="bi bi-play-fill" aria-hidden="true"></i>' + formatDuration(item.durationSeconds);
			card.appendChild(badge);
		}
		if (item.location) {
			var locationBadge = element("span", "timeline-location-badge", item.location);
			locationBadge.title = item.location; card.appendChild(locationBadge);
		}
		return card;
	}
	function addHeadings(date, fragment) {
		if (date.getFullYear() !== state.currentYear) {
			state.currentYear = date.getFullYear(); state.currentMonth = null;
			fragment.appendChild(element("h2", "timeline-year", String(state.currentYear)));
		}
		if (date.getMonth() !== state.currentMonth) {
			state.currentMonth = date.getMonth();
			fragment.appendChild(element("h3", "timeline-month", monthFormatter.format(date)));
		}
	}
	function renderGroups(groups) {
		var target = document.getElementById("timelineContent"); var fragment = document.createDocumentFragment();
		groups.forEach(function (group) {
			var existing = target.querySelector('.timeline-day[data-date="' + group.date + '"]');
			if (existing) {
				var grid = existing.querySelector(".timeline-grid"); group.items.forEach(function (item) { grid.appendChild(renderItem(item)); });
				existing.querySelector(".timeline-day-count").textContent = t("js.timeline.itemCount", grid.children.length); return;
			}
			var date = new Date(group.date + "T12:00:00"); addHeadings(date, fragment);
			var section = element("section", "timeline-day"); section.dataset.date = group.date;
			var heading = element("div", "timeline-day-heading");
			heading.appendChild(element("strong", "timeline-day-number", String(date.getDate())));
			var dayDetails = element("div", "timeline-day-details");
			dayDetails.appendChild(element("span", "timeline-day-weekday", weekdayFormatter.format(date)));
			dayDetails.appendChild(element("span", "muted", monthYearFormatter.format(date)));
			dayDetails.appendChild(element("span", "muted timeline-day-count", t("js.timeline.itemCount", group.items.length)));
			heading.appendChild(dayDetails); section.appendChild(heading);
			var grid = element("div", "timeline-grid"); group.items.forEach(function (item) { grid.appendChild(renderItem(item)); });
			section.appendChild(grid); fragment.appendChild(section);
		}); target.appendChild(fragment); updateVisibleYear();
	}
	function updateVisibleYear() {
		var years = Array.from(document.querySelectorAll(".timeline-year")); if (!years.length) return;
		var active = years[0];
		years.forEach(function (year) { if (year.getBoundingClientRect().top <= 130) active = year; });
		years.forEach(function (year) { year.classList.toggle("active", year === active); });
	}
	function scheduleVisibleYearUpdate() {
		if (yearHighlightScheduled) return; yearHighlightScheduled = true;
		requestAnimationFrame(function () { yearHighlightScheduled = false; updateVisibleYear(); });
	}
	// The origin (subcategory) checkboxes. Read live so pages/navigator always
	// reflect the current selection without a separate state field.
	var originInputs = [];
	function selectedSubcategories() {
		return originInputs.filter(function (input) { return input.checked; }).map(function (input) { return input.value; });
	}
	// Only send the filter when it is a real subset: an empty or full selection
	// means "all", which the API represents as no parameter at all.
	function appendSubcategories(params) {
		var subs = selectedSubcategories();
		if (subs.length > 0 && subs.length < originInputs.length) {
			subs.forEach(function (value) { params.append("subcategories", value); });
		}
		return params;
	}
	function pageUrl(cursor) {
		var params = new URLSearchParams({ type: state.type, limit: "120" }); if (cursor) params.set("cursor", cursor);
		else if (state.from) params.set("from", state.from);
		appendSubcategories(params);
		return "/api/timeline/items?" + params.toString();
	}
	function fetchPage(cursor, signal) {
		return fetch(pageUrl(cursor), { signal: signal }).then(function (response) { if (!response.ok) throw new Error(); return response.json(); });
	}
	function startPrefetch() {
		if (!state.hasMore || state.prefetch) return;
		state.prefetch = { cursor: state.cursor, promise: fetchPage(state.cursor, state.controller.signal) };
	}
	function applyPage(page) {
		renderGroups(page.groups); state.cursor = page.nextCursor; state.hasMore = page.hasMore;
		document.getElementById("timelineEmpty").hidden = state.items.length !== 0; startPrefetch();
	}
	function loadNext() {
		if (state.loading || !state.hasMore) return; state.loading = true;
		var status = document.getElementById("timelineLoadStatus"); status.textContent = t("js.timeline.loadingMore");
		var pending = state.prefetch && state.prefetch.cursor === state.cursor ? state.prefetch.promise : fetchPage(state.cursor, state.controller.signal);
		state.prefetch = null; pending.then(function (page) { applyPage(page); status.textContent = ""; state.loading = false; })
			.catch(function (error) { state.loading = false; if (error.name !== "AbortError") status.textContent = t("js.timeline.loadMoreError"); });
	}
	function resetAndLoad(type, from, historyMode) {
		if (state.controller) state.controller.abort(); state.controller = new AbortController(); state.type = type;
		state.from = from || null;
		state.cursor = null; state.hasMore = false; state.prefetch = null; state.currentYear = null; state.currentMonth = null; state.items = [];
		document.getElementById("timelineContent").replaceChildren(); document.getElementById("timelineEmpty").hidden = true;
		var root = document.getElementById("timelineRoot"); var status = document.getElementById("timelineStatus");
		root.setAttribute("aria-busy", "true"); status.hidden = false; status.textContent = t("js.timeline.loadingLibrary");
		updatePeriodSelection(); updateUrl(historyMode);
		fetchPage(null, state.controller.signal).then(function (page) { applyPage(page); status.hidden = true; root.setAttribute("aria-busy", "false"); })
			.catch(function (error) { if (error.name !== "AbortError") status.textContent = t("js.timeline.loadError"); root.setAttribute("aria-busy", "false"); });
	}
	function updateUrl(mode) {
		if (!mode) return; var params = new URLSearchParams();
		if (state.type !== "ALL") params.set("type", state.type); if (state.from) params.set("date", state.from);
		var url = "/app/timeline" + (params.toString() ? "?" + params.toString() : "");
		history[mode === "push" ? "pushState" : "replaceState"]({ type: state.type, from: state.from }, "", url);
	}
	function updatePeriodSelection() {
		document.querySelectorAll(".timeline-nav-all, .timeline-nav-month").forEach(function (button) {
			var selected = button.classList.contains("timeline-nav-all") ? !state.from : button.dataset.from === state.from;
			button.classList.toggle("active", selected); button.setAttribute("aria-current", selected ? "true" : "false");
		});
	}
	function loadNavigator(type) {
		var target = document.getElementById("timelineNavigatorItems"); target.textContent = t("js.timeline.loading");
		var params = new URLSearchParams({ type: type }); appendSubcategories(params);
		fetch("/api/timeline/index?" + params.toString()).then(function (response) { if (!response.ok) throw new Error(); return response.json(); })
			.then(function (index) {
				target.replaceChildren();
				var allButton = element("button", "timeline-nav-all"); allButton.type = "button";
				allButton.appendChild(element("span", "", t("js.timeline.all"))); allButton.appendChild(element("span", "muted", String(index.datedItems)));
				allButton.addEventListener("click", function () { resetAndLoad(state.type, null, "push"); window.scrollTo({ top: 0, behavior: "smooth" }); });
				target.appendChild(allButton);
				index.years.forEach(function (year) {
					var details = element("details", "timeline-nav-year"); var summary = element("summary", "");
					summary.appendChild(element("span", "", String(year.year))); summary.appendChild(element("span", "muted", String(year.count))); details.appendChild(summary);
					var months = element("div", "timeline-nav-months"); year.months.forEach(function (month) {
						var button = element("button", "timeline-nav-month"); button.type = "button";
						var date = new Date(year.year, month.month - 1, 1); button.appendChild(element("span", "", monthFormatter.format(date)));
						var lastDay = new Date(year.year, month.month, 0).getDate();
						button.appendChild(element("span", "muted", String(month.count))); button.dataset.from = year.year + "-" + String(month.month).padStart(2, "0") + "-" + String(lastDay).padStart(2, "0");
						button.addEventListener("click", function () { resetAndLoad(state.type, button.dataset.from, "push"); window.scrollTo({ top: 0, behavior: "smooth" }); }); months.appendChild(button);
					}); details.appendChild(months); target.appendChild(details);
				});
				if (index.undatedItems > 0) target.appendChild(element("div", "timeline-nav-undated", t("js.timeline.undated", index.undatedItems)));
				updatePeriodSelection();
			}).catch(function () { target.textContent = t("js.timeline.periodsUnavailable"); });
	}
	var geo = { configured: false, dismissed: false, admin: false };
	function geoNoticeHtml(details) {
		// Sidebar notice: only when this media actually carries GPS but has no
		// resolved location and geo isn't configured (and the user hasn't dismissed).
		if (geo.configured || geo.dismissed) return "";
		if (details.latitude == null || (details.location != null && details.location !== "")) return "";
		var link = geo.admin
			? '<a class="geo-notice-link" href="/app/settings#geo-admin">' + t("js.timeline.geo.configure") + '</a>'
			: '<span class="muted">' + t("js.timeline.geo.askAdmin") + '</span>';
		return '<div class="geo-notice geo-notice-inline"><i class="bi bi-geo-alt" aria-hidden="true"></i>'
			+ '<div><strong>' + t("js.timeline.geo.unavailable") + '</strong> ' + t("js.timeline.geo.description") + ' '
			+ link + '</div></div>';
	}
	document.addEventListener("DOMContentLoaded", function () {
		var type = document.getElementById("timelineType"); var query = new URLSearchParams(location.search);
		var root = document.getElementById("timelineRoot"), toolbar = root.querySelector(".timeline-toolbar");
		geo.configured = root.dataset.geoConfigured === "true";
		geo.dismissed = root.dataset.geoNoticeDismissed === "true";
		geo.admin = root.dataset.isAdmin === "true";
		var geoNotice = document.getElementById("geoNotice"), geoDismiss = document.getElementById("geoNoticeDismiss");
		if (geoDismiss) geoDismiss.addEventListener("click", function () {
			var token = document.querySelector("input[name='_csrf']");
			fetch("/app/timeline/geo-notice/dismiss", { method: "POST", headers: { "X-CSRF-TOKEN": token ? token.value : "" } })
				.then(function () { geo.dismissed = true; if (geoNotice) geoNotice.hidden = true; })
				.catch(function () { });
		});
		function updateToolbarHeight() { root.style.setProperty("--timeline-toolbar-height", toolbar.offsetHeight + "px"); }
		updateToolbarHeight(); if (window.ResizeObserver) new ResizeObserver(updateToolbarHeight).observe(toolbar);
		// Initial media-type filter: URL param wins (deep link / back button), otherwise the saved
		// per-user preference the controller passed down, otherwise the default (ALL).
		if (["ALL", "PHOTO", "VIDEO"].includes(query.get("type"))) {
			type.value = query.get("type");
		} else if (["ALL", "PHOTO", "VIDEO"].includes(root.dataset.timelineType)) {
			type.value = root.dataset.timelineType;
		}
		function saveTimelineType(value) {
			var token = document.querySelector("input[name='_csrf']");
			fetch("/app/timeline/type?type=" + encodeURIComponent(value), {
				method: "POST", headers: { "X-CSRF-TOKEN": token ? token.value : "" }
			}).catch(function () { });
		}
		type.addEventListener("change", function () { saveTimelineType(type.value); window.NimbusFileManagerLightbox.close(); loadNavigator(type.value); resetAndLoad(type.value, null, "push"); });
		originInputs = Array.prototype.slice.call(root.querySelectorAll(".timeline-origin-option"));
		function saveSubcategories() {
			var token = document.querySelector("input[name='_csrf']");
			var body = selectedSubcategories().map(function (value) { return "subcategories=" + encodeURIComponent(value); }).join("&");
			fetch("/app/timeline/subcategories", {
				method: "POST",
				headers: { "X-CSRF-TOKEN": token ? token.value : "", "Content-Type": "application/x-www-form-urlencoded" }, body: body
			}).catch(function () { });
		}
		originInputs.forEach(function (input) {
			input.addEventListener("change", function () {
				// Unchecking every origin is treated as "show all": reflect it by re-checking all.
				if (selectedSubcategories().length === 0) originInputs.forEach(function (box) { box.checked = true; });
				saveSubcategories(); window.NimbusFileManagerLightbox.close();
				loadNavigator(type.value); resetAndLoad(type.value, null, "push");
			});
		});
		var observer = new IntersectionObserver(function (entries) { if (entries.some(function (entry) { return entry.isIntersecting; })) loadNext(); }, { rootMargin: "1000px" });
		observer.observe(document.getElementById("timelineSentinel"));
		window.addEventListener("scroll", scheduleVisibleYearUpdate, { passive: true });
		document.addEventListener("nimbus-file-manager:media-details-loaded", function (event) {
			if (event.detail && event.detail.container) {
				// Safe against XSS: geoNoticeHtml() builds only static markup plus i18n bundle
				// strings (t("js.timeline.geo.*"), all app-controlled). The media details
				// (latitude/location) are read solely in its guards, never interpolated into the
				// HTML, so no user-controlled data ever reaches insertAdjacentHTML.
				event.detail.container.insertAdjacentHTML("beforeend", geoNoticeHtml(event.detail.data));
			}
		});
		window.addEventListener("popstate", function () { var current = new URLSearchParams(location.search); var selectedType = current.get("type") || "ALL"; type.value = selectedType; loadNavigator(selectedType); resetAndLoad(selectedType, current.get("date"), null); });
		loadNavigator(type.value); resetAndLoad(type.value, query.get("date"), "replace");
	});
})();