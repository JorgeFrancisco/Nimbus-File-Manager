(function () {
	"use strict";
	// The local-folder picker is now the shared js/folder-picker.js (loaded on
	// every page via layout.html). This file keeps only the settings-specific
	// bits: scroll preservation, the live geo-panel refresh, and the
	// library-switch confirmation on the watch-folder form.
	var scrollKey = "nimbus-file-manager.settings.scroll:" + window.location.pathname;
	var t = window.NimbusFileManagerI18n.t;
	// Marks a refresh that the server did answer, just not with the page. It is the one
	// failure that must still reload, and it has to be told apart from not reaching the
	// server at all - see the catch in refreshOperationPanels().
	var ANSWERED = "answered";

	// The app shell scrolls the inner .main element, not the window (layout.css:
	// .shell is height:100vh and .main is overflow-y:auto). So window.scrollY is
	// always 0 here — track and restore .main.scrollTop instead.
	function scrollContainer() {
		return document.querySelector(".main");
	}

	function restoreScrollPosition() {
		var saved = sessionStorage.getItem(scrollKey);
		if (saved === null) return;
		sessionStorage.removeItem(scrollKey);
		var position = Number(saved);
		if (!Number.isFinite(position) || position < 0) return;
		var apply = function () {
			var container = scrollContainer();
			if (container) container.scrollTop = position;
		};
		window.requestAnimationFrame(function () {
			window.requestAnimationFrame(apply);
		});
		// Re-apply once the whole page (fonts/icons) is laid out: if the double
		// rAF ran before the final height was known, scrollTop would have been
		// clamped and landed short.
		window.addEventListener("load", apply, { once: true });
	}

	function preserveScrollOnSubmit() {
		// Delegated instead of per-form: the operation panels are swapped in place
		// by refreshOperationPanels(), so listeners attached directly to their forms would
		// be lost after the first refresh. Applies to every form on the page (the
		// script only loads on Settings) — earlier it only matched actions under
		// /app/settings, so buttons that post elsewhere but still reload Settings
		// (e.g. "Reexibir aviso de localização" -> /app/timeline/geo-notice/restore)
		// fell through and jumped back to the top.
		document.addEventListener("submit", function (event) {
			var form = event.target;
			if (!form || !form.matches || !form.matches("form")) return;
			if (event.defaultPrevented) return;
			var container = scrollContainer();
			sessionStorage.setItem(scrollKey, String(container ? container.scrollTop : 0));
		});
	}

	// Any panel that runs a background operation opts in with data-operation-panel
	// (its identity) and data-operation-running (whether to keep polling), so the
	// geographic database and the metadata rebuild share one refresh loop instead
	// of one script each.
	function blockedByInventory() {
		var tab = document.querySelector("[data-inventory-running]");
		return !!tab && tab.dataset.inventoryRunning === "true";
	}

	function monitorOperationPanels() {
		// The inventory lock is watched alongside the panels: it disables the whole
		// tab, and before this the screen stayed blocked until the user reloaded by
		// hand, long after the inventory had finished.
		var running = document.querySelector('[data-operation-panel][data-operation-running="true"]');
		if (!running && !blockedByInventory()) return;
		window.setTimeout(refreshOperationPanels, 5000);
	}

	function refreshOperationPanels() {
		// Refresh only the panels in place instead of window.location.reload():
		// a full reload every 5s made the whole page flash during long
		// download/import/rebuild operations.
		fetch(window.location.href, { headers: { "Accept": "text/html" } }).then(function (response) {
			if (!response.ok || response.redirected) throw new Error(ANSWERED);
			return response.text();
		}).then(function (html) {
			var document_ = new DOMParser().parseFromString(html, "text/html");
			var freshTab = document_.querySelector("[data-inventory-running]");
			// Swapping a panel is not enough when the lock clears: it disabled inputs and
			// buttons across every panel of the tab, so the page has to come back whole.
			if (blockedByInventory() && (!freshTab || freshTab.dataset.inventoryRunning !== "true")) {
				window.location.reload();
				return;
			}
			var panels = document.querySelectorAll("[data-operation-panel]");
			if (!panels.length) throw new Error();
			panels.forEach(function (current) {
				var fresh = document_.querySelector('[data-operation-panel="' + current.dataset.operationPanel + '"]');
				if (fresh) current.replaceWith(fresh);
			});
			waitForRestartAfterUpdate(); // the phase only appears in a refreshed panel
			monitorOperationPanels(); // reschedules itself only while an operation is still running
		}).catch(function (error) {
			// Not reaching the server at all, while an update is installing, is the
			// installer taking it down rather than a broken page. Reloading now lands on
			// the browser's own connection-error screen, which runs no JavaScript: the
			// wait below would never start, and the page would only come back if the
			// browser happened to retry by itself. It is also the case the phase check
			// cannot catch, because the server can die between two five-second refreshes
			// without ever having answered STARTING.
			if (error.message !== ANSWERED && updateInstalling()) {
				enterRestartWait();
				return;
			}
			// The server answered, just not with the page (an expired session redirecting
			// to login): fall back to the full reload, which lands wherever it sends us.
			window.location.reload();
		});
	}

	// Downloading, verifying or starting the installer - anything that ends with this
	// server being replaced.
	function updateInstalling() {
		var panel = document.querySelector('[data-operation-panel="update"]');
		return !!panel && panel.dataset.operationRunning === "true";
	}

	// The phase the server answers last, right before the installer starts. Read after
	// every panel swap, because it only ever appears in a refreshed panel - and it is
	// the orderly way in; the catch above is the one for a server that stopped
	// answering without having said so.
	function waitForRestartAfterUpdate() {
		if (document.querySelector('[data-update-phase="STARTING"]')) enterRestartWait();
	}

	// An update is the one operation that takes the server down with it: the
	// installer replaces the files this process runs from, so the application
	// closes, the installer runs, and it opens again by itself. Without this the
	// browser is left showing the page of a server that died - the update worked
	// and the screen never said so, which is indistinguishable from a crash.
	function enterRestartWait() {
		if (enterRestartWait.watching) return;
		enterRestartWait.watching = true;
		var panel = document.querySelector('[data-operation-panel="update"]');
		if (panel) {
			var notice = document.createElement("div");
			notice.className = "alert";
			notice.setAttribute("role", "status");
			notice.textContent = t("js.settings.updateRestarting");
			panel.appendChild(notice);
		}
		// Health is the one endpoint that answers without a session, which matters
		// because the restart drops the old one: polling anything else would answer
		// the login page and read as "back" while the application was still down.
		var attempt = function () {
			fetch("/actuator/health", { cache: "no-store" }).then(function (response) {
				if (!response.ok) throw new Error();
				window.location.reload();
			}).catch(function () {
				window.setTimeout(attempt, 3000);
			});
		};
		// Waits before the first attempt: the server is still up for a few seconds
		// after the answer, and finding it alive now would reload into the version
		// being replaced.
		window.setTimeout(attempt, 8000);
	}

	// The rebuild choices belong to the user, not to a rebuild, so they are stored
	// the moment they change - picking a folder and leaving the screen never
	// discards it, the same contract the conversion screen has.
	function bindMetadataRebuildPreferences() {
		// Delegated from the document instead of bound to the form: the panel holding
		// it is replaced wholesale by the 5s refresh loop above, and a listener on the
		// old node dies with it - from the first refresh on, changing a field silently
		// stopped being stored until the user pressed the button.
		document.addEventListener("change", function (event) {
			var target = event.target;
			var form = target && target.closest ? target.closest('form[action$="/app/settings/metadata/rebuild"]') : null;
			if (!form) return;

			// Serializing the form carries its hidden _csrf field along, which is how
			// Spring accepts the token on a form-encoded post.
			fetch("/app/settings/metadata/preferences", {
				method: "POST",
				headers: { "Content-Type": "application/x-www-form-urlencoded" },
				body: new URLSearchParams(new FormData(form))
			}).catch(function () {
				// A preference that could not be stored is not worth interrupting the
				// user over: the rebuild still uses what is selected on screen.
			});
		});
	}

	// Disabling the offline location opens the dialog that asks what to do with the
	// downloaded data. Purely presentational: both answers post the same form, and the
	// backend decides what each one means.
	function bindGeoDisableDialog() {
		var opener = document.getElementById("geoDisableOpen");
		var overlay = document.getElementById("geoDisableDialog");
		if (!opener || !overlay) return;
		opener.addEventListener("click", function () { overlay.hidden = false; });
		overlay.addEventListener("click", function (event) {
			if (event.target === overlay || event.target.hasAttribute("data-close")) overlay.hidden = true;
		});
		document.addEventListener("keydown", function (event) {
			if (event.key === "Escape") overlay.hidden = true;
		});
	}

	document.addEventListener("DOMContentLoaded", function () {
		restoreScrollPosition();
		waitForRestartAfterUpdate();
		monitorOperationPanels();
		bindMetadataRebuildPreferences();
		bindGeoDisableDialog();
		// The shared folder picker (js/folder-picker.js) fills #watchFolderInput;
		// here we only guard the actual library switch on submit.
		var input = document.getElementById("watchFolderInput");
		if (input) {
			var settingsForm = input.closest("form");
			settingsForm.addEventListener("submit", function (event) {
				var confirmation = settingsForm.querySelector('input[name="confirmLibraryChange"]');
				if (input.value === input.dataset.originalValue || confirmation.value === "true") return;
				event.preventDefault();
				var accepted = window.confirm(t("js.settings.libraryConfirm"));
				if (accepted) { confirmation.value = "true"; settingsForm.requestSubmit(); }
			});
		}
		preserveScrollOnSubmit();
	});
})();