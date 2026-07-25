(function () {
	function bindAutoRefresh() {
		var element = document.querySelector("[data-refresh-ms]");

		if (!element) {
			return;
		}

		var interval = Number(element.getAttribute("data-refresh-ms"));

		if (!(interval > 0)) {
			return;
		}

		// Refresh the marked region in place instead of window.location.reload():
		// a full reload made the page flash on every cycle. The element needs an
		// id so its fresh counterpart can be found even when the new page no
		// longer asks for auto-refresh (e.g. the last execution just finished).
		if (!element.id) {
			window.setTimeout(function () {
				window.location.reload();
			}, interval);
			return;
		}

		window.setTimeout(function () {
			refreshRegion(element.id);
		}, interval);
	}

	function refreshRegion(id) {
		fetch(window.location.href, { headers: { "Accept": "text/html" } }).then(function (response) {
			if (!response.ok || response.redirected) {
				throw new Error();
			}

			return response.text();
		}).then(function (html) {
			var fresh = new DOMParser().parseFromString(html, "text/html").getElementById(id);
			var current = document.getElementById(id);

			if (!fresh || !current) {
				throw new Error();
			}

			current.replaceWith(fresh);

			// Let page scripts re-bind whatever they attached inside the region
			// (e.g. the dashboard's infinite scroll observer).
			document.dispatchEvent(new CustomEvent("nimbus-file-manager:region-refreshed", { detail: { id: id } }));

			var next = Number(fresh.getAttribute("data-refresh-ms"));

			if (next > 0) {
				window.setTimeout(function () {
					refreshRegion(id);
				}, next);
			}
		}).catch(function () {
			// Network error or expired session (redirect to login): fall back to
			// the old full reload, which lands wherever the server sends us.
			window.location.reload();
		});
	}

	function markActiveNavigation() {
		var currentPath = window.location.pathname;

		document.querySelectorAll(".nav a").forEach(function (link) {
			var href = new URL(link.getAttribute("href"), window.location.origin).pathname;
			var isDashboard = href === "/app";
			var isActive = isDashboard ? currentPath === href : currentPath === href
					|| currentPath.startsWith(href + "/");

			link.classList.remove("active");

			if (isActive) {
				link.classList.add("active");
			}
		});
	}

	// Any link marked data-history-back goes to the real previous page instead of a fixed target;
	// its href stays as a fallback for when there is no history (page opened directly).
	function bindHistoryBack() {
		document.querySelectorAll("[data-history-back]").forEach(function (link) {
			link.addEventListener("click", function (event) {
				if (window.history.length > 1) {
					event.preventDefault();
					window.history.back();
				}
			});
		});
	}

	function bindSidebarLogoutTrigger() {
		var trigger = document.getElementById("sidebarLogoutTrigger");
		var form = document.getElementById("logoutForm");

		if (!trigger || !form) {
			return;
		}

		trigger.addEventListener("click", function (event) {
			event.preventDefault();
			form.submit();
		});
	}

	function bindSidebar() {
		// The initial collapsed/expanded state is already rendered server-side by
		// fragments/layout.html (via the sidebarCollapsed model attribute), so there is no
		// fetch-then-toggle flash here - clicking the toggle just flips the class immediately and
		// persists the choice in the background.
		var sidebar = document.querySelector(".sidebar");
		var shell = sidebar ? sidebar.closest(".shell") : null;
		var toggle = document.getElementById("sidebarToggle");

		if (!sidebar || !shell || !toggle) {
			return;
		}

		toggle.addEventListener("click", function () {
			var token = document.querySelector("input[name='_csrf']");
			var collapsed = sidebar.classList.toggle("collapsed");

			shell.classList.toggle("sidebar-collapsed", collapsed);

			fetch("/app/preferences/sidebar", {
				method: "POST",
				headers: {
					"X-CSRF-TOKEN": token ? token.value : ""
				}
			}).catch(function () {});
		});
	}

		document.addEventListener("DOMContentLoaded", function () {
		markActiveNavigation();
		bindSidebar();
		bindSidebarLogoutTrigger();
		bindAutoRefresh();
		bindHistoryBack();
	});
})();