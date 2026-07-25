(function () {
	var t = window.NimbusFileManagerI18n.t;

	/**
	 * Infinite scroll for the Dashboard's "Execucoes" table: as the user scrolls near the bottom,
	 * the next page is fetched as an HTML fragment (see DashboardWebController#executionItems) and
	 * appended to the existing tbody, instead of the fixed top-20 snapshot the old standalone
	 * Execucoes screen used to show. Mirrors files.js's bindInfiniteScroll, simplified since this
	 * table has no view/size/sort - just a page cursor.
	 */
	var currentObserver = null;

	function bindInfiniteScroll() {
		// Re-entrant: also called after app.js swaps the auto-refreshed region,
		// which replaces the executions table and detaches the previous sentinel.
		if (currentObserver) {
			currentObserver.disconnect();
			currentObserver = null;
		}

		var root = document.getElementById("executionsRoot");
		var sentinel = document.getElementById("executionsSentinel");
		var status = document.getElementById("executionsLoadStatus");
		var target = document.getElementById("executionsRows");

		if (!root || !sentinel || !target) {
			return;
		}

		var state = {
			page: parseInt(root.dataset.page, 10) || 0,
			hasNext: root.dataset.hasNext === "true"
		};
		var prefetch = null;
		var loading = false;
		var observer;

		function setStatus(text) {
			if (status) {
				status.textContent = text;
			}
		}

		function fetchPage(page) {
			var params = new URLSearchParams({ page: page });

			return fetch("/app/executions/items?" + params.toString()).then(function (response) {
				return response.text().then(function (html) {
					return { html: html, hasNext: response.headers.get("X-Has-Next") === "true" };
				});
			});
		}

		function extractRows(html) {
			var container = document.createElement("table");
			container.innerHTML = html;

			var wrapper = container.querySelector("tbody");

			return wrapper ? Array.prototype.slice.call(wrapper.children) : [];
		}

		function startPrefetch() {
			if (!state.hasNext || prefetch) {
				return;
			}

			var nextPage = state.page + 1;

			prefetch = { page: nextPage, promise: fetchPage(nextPage) };
		}

		function loadNext() {
			if (loading || !state.hasNext) {
				return;
			}

			loading = true;
			setStatus(t("js.dashboard.loading"));

			var pending = prefetch && prefetch.page === state.page + 1 ? prefetch.promise : fetchPage(state.page + 1);

			prefetch = null;

			pending.then(function (result) {
				extractRows(result.html).forEach(function (row) {
					target.appendChild(row);
				});

				state.page += 1;
				state.hasNext = result.hasNext;
				loading = false;
				setStatus("");

				if (state.hasNext) {
					startPrefetch();
				} else if (observer) {
					observer.disconnect();
				}
			}).catch(function () {
				loading = false;
				setStatus(t("js.dashboard.loadError"));
			});
		}

		if (!state.hasNext || typeof IntersectionObserver === "undefined") {
			return;
		}

		observer = new IntersectionObserver(function (entries) {
			if (entries.some(function (entry) { return entry.isIntersecting; })) {
				loadNext();
			}
		}, { rootMargin: "600px" });

		observer.observe(sentinel);
		currentObserver = observer;
		startPrefetch();
	}

	document.addEventListener("DOMContentLoaded", bindInfiniteScroll);
	document.addEventListener("nimbus-file-manager:region-refreshed", bindInfiniteScroll);
})();