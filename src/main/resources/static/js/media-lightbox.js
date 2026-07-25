(function () {
	"use strict";

	var api = window.NimbusFileManagerLightbox = window.NimbusFileManagerLightbox || {};
	var i18n = window.NimbusFileManagerI18n;
	var t = i18n.t;

	function bind() {
		var lightbox = document.getElementById("mediaLightbox");
		var image = document.getElementById("lightboxImage");
		var video = document.getElementById("lightboxVideo");
		var frame = document.getElementById("lightboxFrame");
		var audio = document.getElementById("lightboxAudio");
		var details = document.getElementById("lightboxDetails");
		var closeButton = lightbox && lightbox.querySelector(".lightbox-close");
		var selectedLink = null;

		if (!lightbox || !image || !video || !frame || !audio || lightbox.dataset.bound === "true") return;
		lightbox.dataset.bound = "true";
		var elements = [image, video, frame, audio];

		function hideAll() {
			video.pause(); video.removeAttribute("src"); video.load();
			audio.pause(); audio.removeAttribute("src"); audio.load();
			frame.removeAttribute("src"); image.removeAttribute("src");
			elements.forEach(function (element) { element.style.display = "none"; });
			lightbox.classList.remove("media-details-open");
		}

		function close() {
			var wasOpen = lightbox.classList.contains("open");
			lightbox.classList.remove("open"); hideAll(); selectedLink = null; document.body.style.overflow = "";
			if (wasOpen) document.dispatchEvent(new CustomEvent("nimbus-file-manager:lightbox-closed"));
		}

		function openElement(element, url, autoplay) {
			hideAll(); selectedLink = null; element.style.display = "block"; element.src = url;
			lightbox.classList.add("open"); document.body.style.overflow = "hidden";
			if (closeButton) closeButton.focus();
			if (autoplay) element.play().catch(function () {});
		}

		function metadataRow(label, value) {
			if (value == null || value === "") return "";
			var escaped = document.createElement("span"); escaped.textContent = String(value);
			return "<dt>" + label + "</dt><dd>" + escaped.innerHTML + "</dd>";
		}

		// Same as metadataRow, plus a "?" icon whose tooltip explains the value
		// (used for the location-confidence level). The hint is trusted i18n text,
		// but is still escaped before landing in the title/aria-label attributes.
		function metadataRowWithHint(label, value, hint) {
			if (value == null || value === "") return "";
			var escaped = document.createElement("span"); escaped.textContent = String(value);
			var suffix = "";
			if (hint) {
				var h = document.createElement("span"); h.textContent = String(hint);
				var attr = h.innerHTML.replace(/"/g, "&quot;");
				suffix = " <i class=\"bi bi-question-circle lightbox-hint\" tabindex=\"0\" role=\"img\" title=\""
					+ attr + "\" aria-label=\"" + attr + "\"></i>";
			}
			return "<dt>" + label + "</dt><dd>" + escaped.innerHTML + suffix + "</dd>";
		}

		// Localized explanation for a confidence level (VERY_HIGH, MEDIUM, ...),
		// or "" when the level is absent or has no registered message.
		function confidenceReason(level) {
			if (!level) return "";
			var key = "js.lightbox.confidenceReason." + level;
			var reason = t(key);
			return reason === key ? "" : reason;
		}

		function formatDuration(seconds) {
			if (seconds == null) return null;
			var value = Math.max(0, Math.round(seconds));
			return Math.floor(value / 60) + ":" + String(value % 60).padStart(2, "0");
		}

		// Mirrors the server-side DateTimeFormatUtils.human: turns the API's raw ISO date
		// (2021-03-25T20:40:50[.SSS]) into dd/MM/yyyy HH:mm:ss.SSS, always with 3 millis digits
		// (.000 when absent) for a consistent column.
		function formatDateTime(iso) {
			if (!iso) return null;
			var m = String(iso).match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?(?:\.(\d+))?/);
			if (!m) return iso;
			var millis = m[7] ? (m[7] + "000").slice(0, 3) : "000";
			return m[3] + "/" + m[2] + "/" + m[1] + " " + m[4] + ":" + m[5] + ":" + (m[6] || "00") + "." + millis;
		}

		function openInventoried(link) {
			selectedLink = link; hideAll();
			var publicId = link.dataset.publicId;
			var isVideo = link.classList.contains("js-lightbox-video") || link.dataset.mediaType === "VIDEO";
			var target = isVideo ? video : image;
			target.style.display = "block"; target.src = "/api/media/" + encodeURIComponent(publicId) + "/content";
			if (!isVideo) target.alt = link.dataset.name || "";
			document.getElementById("lightboxTitle").textContent = link.dataset.name || t("js.lightbox.details");
			var metadata = document.getElementById("lightboxMetadata"); metadata.textContent = t("js.lightbox.loading");
			lightbox.classList.add("open", "media-details-open"); document.body.style.overflow = "hidden";
			if (closeButton) closeButton.focus();

			fetch("/api/media/" + encodeURIComponent(publicId)).then(function (response) {
				if (!response.ok) throw new Error(); return response.json();
			}).then(function (data) {
				if (!selectedLink || selectedLink.dataset.publicId !== publicId) return;
				var camera = [data.manufacturer, data.model].filter(Boolean).join(" ");
				metadata.innerHTML = "<dl>" + metadataRow(t("js.lightbox.capture"), formatDateTime(data.captureDate))
						+ metadataRow(t("js.lightbox.dateSource"), data.dateSourceLabel) + metadataRow(t("js.lightbox.type"), data.typeLabel)
						+ metadataRow(t("js.lightbox.dimensions"), data.width && data.height ? data.width + " × " + data.height : null)
						+ metadataRow(t("js.lightbox.camera"), camera) + metadataRow(t("js.lightbox.duration"), formatDuration(data.durationSeconds))
						+ metadataRow(t("js.lightbox.location"), data.location)
						+ metadataRow(t("js.lightbox.distance"), data.locationDistanceKm == null ? null : Number(data.locationDistanceKm).toLocaleString(i18n.locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) + " km")
						+ metadataRowWithHint(t("js.lightbox.locationConfidence"), data.locationConfidence, confidenceReason(data.locationConfidenceLevel))
						+ metadataRow(t("js.lightbox.locationSource"), data.locationSource)
						+ metadataRow(t("js.lightbox.gps"), data.latitude == null ? null : data.latitude + ", " + data.longitude)
						+ metadataRow(t("js.lightbox.file"), data.currentPath) + "</dl>";
				document.dispatchEvent(new CustomEvent("nimbus-file-manager:media-details-loaded", {
					detail: { data: data, container: metadata, trigger: link }
				}));
			}).catch(function () {
				if (selectedLink && selectedLink.dataset.publicId === publicId) metadata.textContent = t("js.lightbox.unavailable");
			});
		}

		function inventoriedLinks() {
			return Array.from(document.querySelectorAll(".js-lightbox-image[data-public-id], .js-lightbox-video[data-public-id]"));
		}

		function move(delta) {
			var links = inventoriedLinks(); var selectedId = selectedLink && selectedLink.dataset.publicId;
			var index = links.findIndex(function (link) { return link.dataset.publicId === selectedId; });
			var next = index >= 0 ? links[index + delta] : null; if (next) openInventoried(next);
		}

		lightbox.addEventListener("click", close);
		elements.concat([details]).forEach(function (element) {
			element.addEventListener("click", function (event) { event.stopPropagation(); });
		});
		lightbox.querySelector(".previous").addEventListener("click", function (event) { event.stopPropagation(); move(-1); });
		lightbox.querySelector(".next").addEventListener("click", function (event) { event.stopPropagation(); move(1); });
		if (closeButton) closeButton.addEventListener("click", function (event) { event.stopPropagation(); close(); });

		document.addEventListener("keydown", function (event) {
			if (event.key === "Escape") close();
			if (lightbox.classList.contains("media-details-open") && event.key === "ArrowLeft") move(-1);
			if (lightbox.classList.contains("media-details-open") && event.key === "ArrowRight") move(1);
		});

		var triggers = [
			{ selector: ".js-lightbox-image", element: image, autoplay: false },
			{ selector: ".js-lightbox-video", element: video, autoplay: true },
			{ selector: ".js-lightbox-pdf", element: frame, autoplay: false },
			{ selector: ".js-lightbox-text", element: frame, autoplay: false },
			{ selector: ".js-lightbox-audio", element: audio, autoplay: true }
		];

		document.addEventListener("click", function (event) {
			for (var i = 0; i < triggers.length; i++) {
				var trigger = triggers[i]; var link = event.target.closest(trigger.selector);
				if (!link) continue;
				event.preventDefault();
				if (link.dataset.publicId && (link.classList.contains("js-lightbox-image") || link.classList.contains("js-lightbox-video"))) {
					openInventoried(link);
				} else {
					openElement(trigger.element, link.dataset.url, trigger.autoplay);
				}
				return;
			}
		});

		api.close = close;
		api.openInventoried = openInventoried;
	}

	api.bind = bind;
	document.addEventListener("DOMContentLoaded", bind);
})();
