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


	/**
	 * Card menu: the three-dot button and the right click open the same list, and every
	 * action reads the entry from the data-* attributes its row or tile carries. Bound to
	 * the document because infinite scroll appends tiles after load - a listener attached
	 * to the cards that exist at load time would ignore every page but the first.
	 */
	function bindEntryMenu() {
		var menu = document.getElementById("entryMenu");

		if (!menu) {
			return;
		}

		var current = null;

		function entryOf(target) {
			var host = target.closest ? target.closest("[data-entry-path]") : null;

			if (!host) {
				return null;
			}

			return {
				host: host,
				path: host.dataset.entryPath,
				name: host.dataset.entryName,
				directory: host.dataset.entryDirectory === "true"
			};
		}

		function closeMenu() {
			menu.hidden = true;
		}

		function openMenu(entry, x, y) {
			current = entry;

			var root = document.getElementById("explorerRoot");
			var alreadyHere = folderOf(entry.path) === (root ? root.dataset.path : "");

			menu.querySelectorAll("[data-action]").forEach(function (item) {
				var action = item.dataset.action;

				// Downloading a folder means nothing, and every listed entry already lives in
				// the folder on screen - offering to go there would reload the same listing and
				// read as a broken action.
				item.hidden = (action === "download" && entry.directory)
						|| (action === "openFolder" && (entry.directory || alreadyHere));
			});

			menu.hidden = false;

			// Positioned after unhiding so the measured size is the real one, then pulled
			// back inside the viewport when the click landed near the right or bottom edge.
			var rect = menu.getBoundingClientRect();

			menu.style.left = Math.min(x, window.innerWidth - rect.width - 8) + "px";
			menu.style.top = Math.min(y, window.innerHeight - rect.height - 8) + "px";
		}

		document.addEventListener("click", function (event) {
			var trigger = event.target.closest(".tile-menu-open");

			if (!trigger) {
				return;
			}

			event.preventDefault();
			event.stopPropagation();

			var entry = entryOf(trigger);

			if (entry) {
				openMenu(entry, event.clientX, event.clientY);
			}
		});

		/*
		 * Closing on pointerdown rather than click, in the capture phase: pressing a card
		 * starts a navigation and opens the lightbox, and the click that was supposed to
		 * close the menu never arrived - so the menu sat there over the next screen.
		 */
		document.addEventListener("pointerdown", function (event) {
			if (!event.target.closest("#entryMenu") && !event.target.closest(".tile-menu-open")) {
				closeMenu();
			}
		}, true);

		// The menu is positioned from the click coordinates, so scrolling or resizing
		// would strand it far from the item it belongs to.
		window.addEventListener("scroll", closeMenu, true);
		window.addEventListener("resize", closeMenu);

		document.addEventListener("contextmenu", function (event) {
			var entry = entryOf(event.target);

			if (!entry) {
				return;
			}

			event.preventDefault();
			openMenu(entry, event.clientX, event.clientY);
		});

		document.addEventListener("keydown", function (event) {
			if (event.key === "Escape") {
				closeMenu();
			}
		});

		menu.addEventListener("click", function (event) {
			var item = event.target.closest("[data-action]");

			if (!item || !current) {
				return;
			}

			closeMenu();
			runAction(item.dataset.action, current);
		});
	}

	function csrfToken() {
		var field = document.querySelector("input[name='_csrf']");

		return field ? field.value : "";
	}

	function post(url, params) {
		return fetch(url, {
			method: "POST",
			headers: { "Content-Type": "application/x-www-form-urlencoded", "X-CSRF-TOKEN": csrfToken() },
			body: new URLSearchParams(params)
		}).then(function (response) {
			return response.json();
		});
	}

	function browseTo(path) {
		var root = document.getElementById("explorerRoot");
		var query = new URLSearchParams({
			path: path,
			view: root.dataset.view,
			size: root.dataset.size,
			sort: root.dataset.sort
		});

		// Navigating on the next frame: assigning the location straight away starts the
		// request before the browser repaints, so the menu stayed visible on screen for
		// the whole load even though it had already been hidden.
		window.requestAnimationFrame(function () {
			window.location.href = "/app/files?" + query.toString();
		});
	}

	function dialog(id) {
		var overlay = document.getElementById(id);

		overlay.hidden = false;

		overlay.querySelectorAll("[data-close]").forEach(function (button) {
			button.onclick = function () {
				overlay.hidden = true;
			};
		});

		return overlay;
	}

	/**
	 * Shows what the server said and reloads on success so the listing matches disk
	 * again. The sentence is always the backend's: it is the side that knows why an
	 * action was refused, and a refusal with no explanation reads as a silent no-op.
	 *
	 * A pending answer is neither: the command was accepted and is being carried out
	 * elsewhere, so the screen says so and then watches the execution it was handed.
	 */
	function announce(result) {
		window.alert(result.message);

		if (result.pending) {
			watchExecution(result.executionId);
			return;
		}

		if (result.success) {
			window.location.reload();
		}
	}

	/**
	 * Follows a command that outlived the wait, by asking the execution it left
	 * behind - the same row every other screen reads, never a channel of its own.
	 * An ending that is not plain success is told, because an action that did not
	 * happen has to say why; a successful one only refreshes, since interrupting
	 * someone to confirm that what they asked for happened is noise.
	 */
	function watchExecution(executionId) {
		if (!executionId) {
			return;
		}

		window.setTimeout(function () {
			fetch("/api/executions/" + executionId)
					.then(function (response) { if (!response.ok) throw new Error(); return response.json(); })
					.then(function (execution) {
						if (!execution.finished) {
							watchExecution(executionId);
							return;
						}

						if (execution.status !== "FINISHED") {
							window.alert(execution.message);
						}

						window.location.reload();
					})
					.catch(function () { /* the listing refreshes on its own schedule anyway */ });
		}, 1500);
	}

	function failed() {
		window.alert(t("js.files.actionFailed"));
	}

	function folderOf(path) {
		return path.substring(0, Math.max(path.lastIndexOf("\\"), path.lastIndexOf("/")));
	}

	/**
	 * Opening a folder browses into it; opening a file opens the file. The card
	 * already knows how to show every previewable type through the lightbox, so the
	 * menu clicks that same link instead of reimplementing the choice - and a type
	 * with no preview falls back to the raw content in a new tab.
	 */
	function openEntry(entry) {
		if (entry.directory) {
			browseTo(entry.path);
			return;
		}

		var link = entry.host ? entry.host.querySelector("a.media-card-open") : null;

		if (link) {
			link.click();
			return;
		}

		window.open("/app/files/preview?path=" + encodeURIComponent(entry.path), "_blank");
	}

	function runAction(action, entry) {
		if (action === "open") {
			openEntry(entry);
		} else if (action === "openFolder") {
			browseTo(folderOf(entry.path));
		} else if (action === "download") {
			window.location.href = "/app/files/preview?path=" + encodeURIComponent(entry.path);
		} else if (action === "copyPath") {
			copyPath(entry.path);
		} else if (action === "properties") {
			showProperties(entry);
		} else if (action === "rename") {
			askNewName(entry);
		} else if (action === "delete") {
			askDeleteMode(entry);
		}
	}

	function copyPath(path) {
		if (!navigator.clipboard) {
			window.prompt(t("js.files.copyPath"), path);
			return;
		}

		navigator.clipboard.writeText(path).then(function () {
			window.alert(t("js.files.pathCopied"));
		}, function () {
			window.prompt(t("js.files.copyPath"), path);
		});
	}

	function loadProperties(path) {
		return fetch("/api/files/properties?path=" + encodeURIComponent(path)).then(function (response) {
			return response.json();
		});
	}

	function showProperties(entry) {
		var overlay = dialog("propertiesDialog");

		loadProperties(entry.path).then(function (properties) {
			document.getElementById("propName").textContent = properties.name;
			document.getElementById("propLocation").textContent = properties.parentPath || properties.path;
			document.getElementById("propType").textContent = properties.typeLabel;
			document.getElementById("propSize").textContent = properties.sizeLabel;
			document.getElementById("propCreated").textContent = properties.createdAtLabel;
			document.getElementById("propModified").textContent = properties.modifiedAtLabel;
			document.getElementById("propCatalog").textContent = properties.catalogLabel || "";
			document.getElementById("propFiles").textContent = properties.fileCount;
			document.getElementById("propFolders").textContent = properties.folderCount;

			overlay.querySelectorAll("[data-folder-only]").forEach(function (row) {
				row.hidden = !properties.directory;
			});

			overlay.querySelectorAll("[data-file-only]").forEach(function (row) {
				row.hidden = properties.directory;
			});
		}).catch(function () {
			overlay.hidden = true;
			failed();
		});
	}

	function askNewName(entry) {
		var overlay = dialog("renameDialog");
		var input = document.getElementById("renameInput");

		input.value = entry.name;
		input.focus();
		input.select();

		document.getElementById("renameConfirm").onclick = function () {
			overlay.hidden = true;

			post("/api/files/rename", { path: entry.path, newName: input.value }).then(announce).catch(failed);
		};
	}

	function askDeleteMode(entry) {
		var overlay = dialog("deleteDialog");

		document.getElementById("deleteDialogQuestion").textContent = t("js.files.deleteQuestion", entry.name);

		document.getElementById("deleteQuarantine").onclick = function () {
			overlay.hidden = true;

			post("/api/files/delete", { path: entry.path, mode: "QUARANTINE" }).then(announce).catch(failed);
		};

		document.getElementById("deletePermanent").onclick = function () {
			overlay.hidden = true;

			// Erasing a folder takes everything under it, so the amount at stake is stated
			// in a second dialog before the button that cannot be undone.
			if (entry.directory) {
				confirmFolderDeletion(entry);
			} else {
				deleteForGood(entry);
			}
		};
	}

	function confirmFolderDeletion(entry) {
		loadProperties(entry.path).then(function (properties) {
			var overlay = dialog("folderConfirmDialog");

			document.getElementById("folderConfirmWarning").textContent = t("js.files.folderWarning",
					properties.fileCount, properties.sizeLabel);

			document.getElementById("folderConfirmAction").onclick = function () {
				overlay.hidden = true;
				deleteForGood(entry);
			};
		}).catch(failed);
	}

	function deleteForGood(entry) {
		post("/api/files/delete", { path: entry.path, mode: "PERMANENT" }).then(announce).catch(failed);
	}

	document.addEventListener("DOMContentLoaded", function () {
		bindBackButton();
		bindBreadcrumb();
		bindRefreshButton();
		bindInfiniteScroll();
		bindEntryMenu();
	});
})();