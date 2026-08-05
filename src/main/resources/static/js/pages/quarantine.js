(function () {
	"use strict";

	// Quarentena screen. Single restores go through POST /app/quarantine/restore and the server
	// decides the outcome (restored, needs a conflict decision, needs an alternate folder, ...); this
	// script reacts to that, driving the conflict/origin dialogs. Bulk restore of the checked files
	// uses POST /app/quarantine/restore-selected. View modes are plain server links, and opening a
	// file is handled by the global lightbox (media-lightbox.js) via the shared media-card markup, so
	// neither needs code here. CSRF mirrors app.js.

	var pending = null; // { movementId, fileName, destinationFolder }
	var t = window.NimbusFileManagerI18n.t;
	var selectionStorageKey = "nimbus-file-manager.quarantine.selection.v1";
	var selection = loadSelection();

	function el(id) {
		return document.getElementById(id);
	}

	function csrf() {
		var token = document.querySelector("input[name='_csrf']");
		return token ? token.value : "";
	}

	function postJson(url, body) {
		return fetch(url, {
			method: "POST",
			headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": csrf() },
			body: JSON.stringify(body)
		}).then(function (response) {
			if (!response.ok) {
				throw new Error("HTTP " + response.status);
			}
			return response.json();
		});
	}

	function status(message, kind) {
		var box = el("quarantineStatus");
		if (!box) {
			return;
		}
		box.className = "alert section" + (kind ? " " + kind : "");
		box.textContent = message;
		box.hidden = false;
	}

	function reloadSoon() {
		window.setTimeout(function () { window.location.reload(); }, 700);
	}

	// ---- Selection -----------------------------------------------------------

	function loadSelection() {
		try {
			var stored = JSON.parse(window.localStorage.getItem(selectionStorageKey) || "[]");
			if (!Array.isArray(stored)) {
				return {};
			}
			return stored.reduce(function (result, id) {
				if (typeof id === "string" && id) {
					result[id] = true;
				}
				return result;
			}, {});
		} catch (error) {
			return {};
		}
	}

	function saveSelection() {
		try {
			window.localStorage.setItem(selectionStorageKey, JSON.stringify(Object.keys(selection)));
		} catch (error) {
			// Selection still works for this page when storage is unavailable.
		}
	}

	function setSelected(box, checked) {
		var id = box.getAttribute("data-movement-id");
		box.checked = checked;
		if (!id) {
			return;
		}
		if (checked) {
			selection[id] = true;
		} else {
			delete selection[id];
		}
	}

	function discardSelection(ids) {
		ids.forEach(function (id) { delete selection[id]; });
		saveSelection();
	}

	function selectableBoxes() {
		return Array.prototype.slice.call(document.querySelectorAll(".js-select:not([disabled])"));
	}

	function selectedIds() {
		return Object.keys(selection);
	}

	function refreshSelection() {
		var ids = selectedIds();
		var count = el("quarantineSelectedCount");
		var button = document.querySelector(".js-restore-selected");
		var deleteCount = el("quarantineDeleteCount");
		var deleteButton = document.querySelector(".js-delete-selected");
		var all = el("quarantineSelectAll");

		if (count) {
			count.textContent = ids.length;
		}
		if (button) {
			button.disabled = ids.length === 0;
		}
		if (deleteCount) {
			deleteCount.textContent = ids.length;
		}
		if (deleteButton) {
			deleteButton.disabled = ids.length === 0;
		}
		if (all) {
			var boxes = selectableBoxes();
			var checkedOnPage = boxes.filter(function (box) { return box.checked; }).length;
			all.checked = boxes.length > 0 && checkedOnPage === boxes.length;
			all.indeterminate = checkedOnPage > 0 && checkedOnPage < boxes.length;
		}
	}

	function bindSelection() {
		document.querySelectorAll(".js-select").forEach(function (box) {
			var id = box.getAttribute("data-movement-id");
			if (box.disabled) {
				delete selection[id];
			} else {
				box.checked = Boolean(selection[id]);
			}
			box.addEventListener("change", function () {
				setSelected(box, box.checked);
				saveSelection();
				refreshSelection();
			});
		});
		saveSelection();

		var all = el("quarantineSelectAll");
		if (all) {
			all.addEventListener("change", function () {
				selectableBoxes().forEach(function (box) { setSelected(box, all.checked); });
				saveSelection();
				refreshSelection();
			});
		}

		var button = document.querySelector(".js-restore-selected");
		if (button) {
			button.addEventListener("click", function () {
				var ids = selectedIds();
				if (!ids.length) {
					return;
				}
				button.disabled = true;
				postJson("/app/quarantine/restore-selected", { ids: ids }).then(function () {
					discardSelection(ids);
					status(t("js.quarantine.restoreQueued"), "ok");
					followBatch(function (result) {
						status(result.message || t("js.quarantine.restoreCompleted"),
								result.errors > 0 || result.skipped > 0 ? "warn" : "ok");
						reloadSoon();
					}, function () {
						button.disabled = false;
						status(t("js.quarantine.restoreSelectedError"), "error");
					});
				}).catch(function () {
					button.disabled = false;
					status(t("js.quarantine.restoreSelectedError"), "error");
				});
			});
		}

		bindDeleteSelected();
		bindOutcomeDialog();
		bindCleanupAbsent();
		refreshSelection();
	}

	function bindCleanupAbsent() {
		var button = document.querySelector(".js-cleanup-absent");
		if (!button) {
			return;
		}
		button.addEventListener("click", function () {
			button.disabled = true;
			postJson("/app/quarantine/cleanup-absent", {}).then(function (result) {
				// Nothing was absent, so nothing ran: that is read in a dialog, because a
				// status line saying zero is read as the button having worked. The reason
				// arrives written - the screen only shows it.
				if (!result.pending && !result.removed) {
					button.disabled = false;
					showOutcome(result.message, reloadSoon);
					return;
				}
				status(result.message, "ok");
				if (!result.pending) {
					reloadSoon();
					return;
				}
				followBatch(function (finished) {
					status(finished.message || result.message, finished.errors > 0 ? "warn" : "ok");
					reloadSoon();
				}, function () {
					button.disabled = false;
					status(t("js.quarantine.cleanupError"), "error");
				});
			}).catch(function () {
				button.disabled = false;
				status(t("js.quarantine.cleanupError"), "error");
			});
		});
	}

	// What to do once the reason has been read. The list still has to refresh, but
	// refreshing behind the dialog wiped it after 700 milliseconds - long enough to
	// see something appear, not to read why nothing was deleted.
	var afterOutcome = null;

	function showOutcome(message, onDismiss) {
		var dialog = el("quarantineOutcomeDialog");
		var body = el("quarantineOutcomeMessage");

		if (!dialog || !body) {
			status(message, "warn");

			if (onDismiss) {
				onDismiss();
			}

			return;
		}

		afterOutcome = onDismiss || null;
		body.textContent = message;
		dialog.showModal();
	}

	function bindOutcomeDialog() {
		var dialog = el("quarantineOutcomeDialog");

		if (!dialog) {
			return;
		}

		var close = dialog.querySelector(".js-outcome-close");

		if (close) {
			close.addEventListener("click", function () { dialog.close(); });
		}

		// Listens for close rather than for the button: Esc and the backdrop dismiss
		// the dialog too, and the refresh has to follow every one of them.
		dialog.addEventListener("close", function () {
			var pending = afterOutcome;

			afterOutcome = null;

			if (pending) {
				pending();
			}
		});
	}

	function bindDeleteSelected() {
		var trigger = document.querySelector(".js-delete-selected");
		var dialog = el("quarantineDeleteDialog");
		if (!trigger || !dialog) {
			return;
		}

		trigger.addEventListener("click", function () {
			var ids = selectedIds();
			if (!ids.length) {
				return;
			}
			var count = el("quarantineDeleteCountDialog");
			if (count) {
				count.textContent = ids.length;
			}
			dialog.showModal();
		});

		dialog.querySelector(".js-delete-cancel").addEventListener("click", function () { dialog.close(); });
		dialog.querySelector(".js-delete-confirm").addEventListener("click", function () {
			var ids = selectedIds();
			dialog.close();
			if (!ids.length) {
				return;
			}
			postJson("/app/quarantine/delete-selected", { ids: ids }).then(function () {
				discardSelection(ids);
				status(t("js.quarantine.purgeQueued"), "ok");

				followBatch(function (result) {
					status(t("js.quarantine.purgeCompleted", result.succeeded || 0, result.errors || 0),
							result.errors > 0 || result.skipped > 0 ? "warn" : "ok");

					// Nothing deleted is never a success: the outcome says why - most often a
					// conversion holding the quarantine folder while it moves originals into
					// it. The refresh waits for the dialog, or it would take the reason with it.
					if (!result.succeeded && result.total > 0 && result.message) {
						showOutcome(result.message, reloadSoon);
					} else {
						reloadSoon();
					}
				}, function () {
					status(t("js.quarantine.purgeError"), "error");
				});
			}).catch(function () {
				status(t("js.quarantine.purgeError"), "error");
			});
		});
	}

	// ---- Following a batch that runs in the worker --------------------------

	// The restore and the purge of a selection stopped being answered by the request
	// that asked for them: a worker does the moving, and what the screen can do is
	// watch the row. Polled rather than pushed because that is what every other long
	// operation here already does, and one more mechanism would be one more to keep.
	var BATCH_POLL_MILLIS = 1000;
	var BATCH_MAX_POLLS = 600;

	function followBatch(onDone, onError) {
		var polls = 0;

		function ask() {
			polls += 1;

			fetch("/app/quarantine/progress").then(function (response) {
				if (!response.ok) {
					throw new Error("HTTP " + response.status);
				}

				return response.json();
			}).then(function (result) {
				if (result && result.running && polls < BATCH_MAX_POLLS) {
					window.setTimeout(ask, BATCH_POLL_MILLIS);

					return;
				}

				onDone(result || {});
			}).catch(onError);
		}

		window.setTimeout(ask, BATCH_POLL_MILLIS);
	}

	// ---- Single restore + dialogs -------------------------------------------

	function restoreItem(movementId, options) {
		var body = {
			movementId: movementId,
			conflict: options && options.conflict ? options.conflict : "BLOCK",
			destinationFolder: options && options.destinationFolder ? options.destinationFolder : null
		};

		return postJson("/app/quarantine/restore", body).then(function (result) {
			handleOutcome(result, movementId, body.destinationFolder);
		}).catch(function () {
			status(t("js.quarantine.restoreErrorRetry"), "error");
		});
	}

	function handleOutcome(result, movementId, destinationFolder) {
		switch (result.outcome) {
		case "RESTORED":
			discardSelection([movementId]);
			status(t("js.quarantine.restoredTo", result.restoredPath || t("js.quarantine.originLocation")), "ok");
			reloadSoon();
			break;
		// The decision was taken and the move is a worker's now. Nothing left to ask,
		// so the screen stops waiting on the request and starts watching the row.
		case "PENDING":
			discardSelection([movementId]);
			status(result.message, "ok");
			followBatch(function (finished) {
				status(finished.message || result.message, finished.errors > 0 ? "warn" : "ok");
				reloadSoon();
			}, function () {
				status(t("js.quarantine.restoreError"), "error");
			});
			break;
		case "CONFLICT":
			openConflictDialog(movementId, destinationFolder);
			break;
		case "ORIGIN_MISSING":
			openOriginDialog(movementId);
			break;
		case "SKIPPED":
			status(t("js.quarantine.kept"), "");
			break;
		case "MISSING_IN_QUARANTINE":
			discardSelection([movementId]);
			status(result.message || t("js.quarantine.missing"), "warn");
			reloadSoon();
			break;
		case "LOCKED":
			status(result.message || t("js.quarantine.locked"), "warn");
			break;
		default:
			status(result.message || t("js.quarantine.restoreError"), "error");
			break;
		}
	}

	function pendingFileName(movementId) {
		var button = document.querySelector(".js-restore-item[data-movement-id='" + movementId + "']");
		return button ? button.getAttribute("data-file-name") : t("js.quarantine.file");
	}

	function openConflictDialog(movementId, destinationFolder) {
		pending = { movementId: movementId, fileName: pendingFileName(movementId), destinationFolder: destinationFolder || null };
		var name = el("quarantineConflictName");
		if (name) {
			name.textContent = pending.fileName;
		}
		var dialog = el("quarantineConflictDialog");
		if (dialog) {
			dialog.showModal();
		}
	}

	function openOriginDialog(movementId) {
		pending = { movementId: movementId, fileName: pendingFileName(movementId), destinationFolder: null };
		var name = el("quarantineOriginName");
		if (name) {
			name.textContent = pending.fileName;
		}
		var chosen = el("quarantineChosenFolder");
		if (chosen) {
			chosen.textContent = t("js.quarantine.none");
		}
		var input = el("restoreDestinationInput");
		if (input) {
			input.value = "";
		}
		var restoreButton = document.querySelector(".js-origin-restore");
		if (restoreButton) {
			restoreButton.disabled = true;
		}
		var dialog = el("quarantineOriginDialog");
		if (dialog) {
			dialog.showModal();
		}
	}

	function bindItemButtons() {
		document.querySelectorAll(".js-restore-item").forEach(function (button) {
			button.addEventListener("click", function () {
				if (button.getAttribute("data-present") === "false") {
					return;
				}
				restoreItem(button.getAttribute("data-movement-id"), {});
			});
		});
	}

	function bindConflictDialog() {
		var dialog = el("quarantineConflictDialog");
		if (!dialog) {
			return;
		}
		dialog.querySelector(".js-conflict-cancel").addEventListener("click", function () { dialog.close(); });
		dialog.querySelector(".js-conflict-skip").addEventListener("click", function () {
			dialog.close();
			status(t("js.quarantine.kept"), "");
		});
		dialog.querySelector(".js-conflict-rename").addEventListener("click", function () {
			if (!pending) {
				return;
			}
			dialog.close();
			restoreItem(pending.movementId, { conflict: "RENAME", destinationFolder: pending.destinationFolder });
		});
	}

	function bindOriginDialog() {
		var dialog = el("quarantineOriginDialog");
		if (!dialog) {
			return;
		}
		var input = el("restoreDestinationInput");
		var restoreButton = dialog.querySelector(".js-origin-restore");
		var chosen = el("quarantineChosenFolder");

		if (input) {
			// The shared folder picker writes the chosen path here and dispatches "change".
			input.addEventListener("change", function () {
				var value = input.value ? input.value.trim() : "";
				if (chosen) {
					chosen.textContent = value || t("js.quarantine.none");
				}
				if (restoreButton) {
					restoreButton.disabled = !value;
				}
			});
		}

		dialog.querySelector(".js-origin-cancel").addEventListener("click", function () { dialog.close(); });
		restoreButton.addEventListener("click", function () {
			if (!pending || !input || !input.value) {
				return;
			}
			var destination = input.value.trim();
			dialog.close();
			pending.destinationFolder = destination;
			restoreItem(pending.movementId, { conflict: "BLOCK", destinationFolder: destination });
		});
	}

	document.addEventListener("DOMContentLoaded", function () {
		bindSelection();
		bindItemButtons();
		bindConflictDialog();
		bindOriginDialog();
	});
})();