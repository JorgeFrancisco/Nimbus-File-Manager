(function () {
	var t = window.NimbusFileManagerI18n.t;

	function bindBackButton() {
		var historyKey = "nimbusFileManager.files.history";
		var currentUrl = window.location.href;
		var root = document.getElementById("explorerRoot");
		var currentPath = root ? root.dataset.path : "";
		var state;
		try { state = JSON.parse(sessionStorage.getItem(historyKey)) || {}; } catch (error) { state = {}; }
		state.entries = Array.isArray(state.entries) ? state.entries : [];
		var backButton = document.getElementById("explorerBack");

		if (!backButton) {
			return;
		}

		if (state.currentUrl && state.currentPath && state.currentPath !== currentPath) {
			state.entries.push({ url: state.currentUrl, path: state.currentPath });
			state.entries = state.entries.slice(-10);
		}
		state.currentUrl = currentUrl; state.currentPath = currentPath;
		sessionStorage.setItem(historyKey, JSON.stringify(state));

		if (!state.entries.length) {
			backButton.classList.add("disabled");
			backButton.disabled = true;
		} else {
			backButton.title = t("js.files.backHistory", state.entries.length);
		}

		backButton.addEventListener("click", function () {
			var target = state.entries.pop();
			if (target) {
				state.currentUrl = target.url; state.currentPath = target.path;
				sessionStorage.setItem(historyKey, JSON.stringify(state));
				window.location.assign(target.url);
			}
		});
	}

	/**
	 * Renders the current folder as a clickable trail (drive/segment/.../current) above the
	 * toolbar, built from the same path/view/size/sort state the address bar and view-switch links
	 * already use, so jumping back to an ancestor folder doesn't require retyping the address.
	 */
	function bindBreadcrumb() {
		var root = document.getElementById("explorerRoot");
		var breadcrumb = document.getElementById("explorerBreadcrumb");

		if (!root || !breadcrumb) {
			return;
		}

		var path = root.dataset.path || "";

		if (!path) {
			return;
		}

		var normalized = path.replace(/\\/g, "/");
		var segments = normalized.split("/").filter(function (segment) { return segment.length > 0; });
		var isWindowsDrive = /^[A-Za-z]:$/.test(segments[0] || "");
		var accumulated = "";

		segments.forEach(function (segment, index) {
			var isLast = index === segments.length - 1;

			accumulated = index === 0 ? segment : accumulated + "/" + segment;

			var crumbPath = accumulated + (isWindowsDrive && index === 0 ? "/" : "");
			var item = document.createElement(isLast ? "span" : "a");

			item.className = "explorer-breadcrumb-item" + (isLast ? " current" : "");
			item.textContent = segment;

			if (!isLast) {
				item.href = breadcrumbUrl(root, crumbPath);
			}

			breadcrumb.appendChild(item);

			if (!isLast) {
				var separator = document.createElement("i");

				separator.className = "bi bi-chevron-right";
				separator.setAttribute("aria-hidden", "true");
				breadcrumb.appendChild(separator);
			}
		});
	}

	function breadcrumbUrl(root, path) {
		var params = new URLSearchParams({
			path: path,
			view: root.dataset.view || "",
			size: root.dataset.size || "",
			sort: root.dataset.sort || ""
		});

		return "/app/files?" + params.toString();
	}

	function bindRefreshButton() {
		var refreshButton = document.getElementById("explorerRefresh");
		var refreshing = false;

		function refreshListing() {
			if (refreshing) return Promise.resolve(); refreshing = true;
			var status = document.getElementById("explorerLoadStatus");
			return fetch(window.location.href, { headers: { "X-Requested-With": "XMLHttpRequest" } })
					.then(function (response) { if (!response.ok) throw new Error(); return response.text(); })
					.then(function (html) {
						var documentCopy = new DOMParser().parseFromString(html, "text/html");
						var currentTarget = document.getElementById("explorerRows") || document.getElementById("explorerTiles");
						var freshTarget = documentCopy.getElementById(currentTarget && currentTarget.id);
						var currentSummary = document.querySelector(".explorer-summary");
						var freshSummary = documentCopy.querySelector(".explorer-summary");
						var freshRoot = documentCopy.getElementById("explorerRoot");
						if (!currentTarget || !freshTarget || !freshRoot) throw new Error();
						currentTarget.innerHTML = freshTarget.innerHTML;
						if (currentSummary && freshSummary) currentSummary.innerHTML = freshSummary.innerHTML;
						var root = document.getElementById("explorerRoot");
						root.dataset.page = freshRoot.dataset.page || "0";
						root.dataset.hasNext = freshRoot.dataset.hasNext || "false";
						document.dispatchEvent(new CustomEvent("nimbus-file-manager:files-list-refreshed"));
						if (status) status.textContent = t("js.files.updated");
						window.setTimeout(function () { if (status && status.textContent === t("js.files.updated")) status.textContent = ""; }, 1500);
					}).catch(function () { if (status) status.textContent = t("js.files.updateError"); })
					.finally(function () { refreshing = false; });
		}

		if (refreshButton) {
			refreshButton.addEventListener("click", function () {
				refreshListing();
			});
		}

		document.addEventListener("nimbus-file-manager:execution-finished", function (event) {
			if (event.detail && event.detail.executionType === "INVENTORY") {
				refreshListing();
			}
		});

		function scheduleRefresh() {
			window.setTimeout(function () {
				if (document.hidden) { scheduleRefresh(); return; }
				refreshListing().finally(scheduleRefresh);
			}, 15000);
		}
		scheduleRefresh();
	}

	/**
	 * Infinite scroll for the file listing: as the user scrolls near the bottom, the next page is
	 * fetched as an HTML fragment (see FileExplorerWebController#items) and appended to the
	 * existing table/grid instead of navigating to a new "Proxima" page. To avoid a visible pause
	 * while that fetch happens, the next page is pre-fetched in the background right after the
	 * current one finishes rendering, so by the time the user actually reaches the bottom it's
	 * usually already sitting in memory ready to append.
	 */
	function bindInfiniteScroll() {
		var root = document.getElementById("explorerRoot");
		var sentinel = document.getElementById("explorerSentinel");
		var status = document.getElementById("explorerLoadStatus");
		var rowsTarget = document.getElementById("explorerRows");
		var tilesTarget = document.getElementById("explorerTiles");
		var target = rowsTarget || tilesTarget;

		if (!root || !sentinel || !target) {
			return;
		}

		var isTable = !!rowsTarget;
		var state = {
			path: root.dataset.path || "",
			view: root.dataset.view || "",
			size: root.dataset.size || "",
			sort: root.dataset.sort || "",
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
			var params = new URLSearchParams({
				path: state.path,
				view: state.view,
				size: state.size,
				sort: state.sort,
				page: page
			});

			return fetch("/app/files/items?" + params.toString()).then(function (response) {
				return response.text().then(function (html) {
					return { html: html, hasNext: response.headers.get("X-Has-Next") === "true" };
				});
			});
		}

		function extractItems(html) {
			var container = document.createElement(isTable ? "table" : "div");
			container.innerHTML = html;

			var wrapper = isTable ? container.querySelector("tbody") : container.firstElementChild;

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
			setStatus(t("js.files.loading"));

			var pending = prefetch && prefetch.page === state.page + 1 ? prefetch.promise : fetchPage(state.page + 1);

			prefetch = null;

			pending.then(function (result) {
				extractItems(result.html).forEach(function (item) {
					target.appendChild(item);
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
				setStatus(t("js.files.loadError"));
			});
		}

		function observeNextPages() {
			if (!state.hasNext || typeof IntersectionObserver === "undefined") return;
			observer = new IntersectionObserver(function (entries) {
				if (entries.some(function (entry) { return entry.isIntersecting; })) loadNext();
			}, { rootMargin: "600px" });
			observer.observe(sentinel); startPrefetch();
		}

		document.addEventListener("nimbus-file-manager:files-list-refreshed", function () {
			if (observer) observer.disconnect();
			state.page = parseInt(root.dataset.page, 10) || 0;
			state.hasNext = root.dataset.hasNext === "true";
			prefetch = null; loading = false;
			observeNextPages();
		});

		observeNextPages();
	}

	document.addEventListener("DOMContentLoaded", function () {
		bindBackButton();
		bindBreadcrumb();
		bindRefreshButton();
		bindInfiniteScroll();
	});
})();