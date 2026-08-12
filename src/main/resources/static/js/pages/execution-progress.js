(function () {
	var executionStatus = window.NimbusFileManagerExecutionStatus;
	var t = window.NimbusFileManagerI18n.t;

	// Holds a "resume poll()" callback while the tab is hidden, instead of relying on the
	// browser's own background-tab timer throttling (which is inconsistent across browsers and
	// can still leak a request every minute or two). A forgotten/backgrounded tab therefore sends
	// zero requests until the user actually looks at it again.
	var resumeOnVisible = null;

	document.addEventListener("visibilitychange", function () {
		if (!document.hidden && resumeOnVisible) {
			var resume = resumeOnVisible;

			resumeOnVisible = null;
			resume();
		}
	});

	function resultUrl(executionId, kind) {
		if (kind === "organization-preview") {
			return "/app/organization/preview/" + executionId;
		}

		return "/app/executions/" + executionId;
	}

	function render(data, executionId, kind) {
		document.getElementById("statusValue").textContent = data.statusLabel;
		document.getElementById("foundValue").textContent = data.filesFound;
		document.getElementById("totalValue").textContent = data.totalExpected != null ? data.totalExpected : "-";
		document.getElementById("errorsValue").textContent = data.errors;

		// Both organization flows are the same executor loop (preview is execute in
		// dry-run), so they report identically: the running counter lives in filesAnalyzed
		// and the final moved count in filesMoved; skipped lives in cacheHits throughout.
		// Only the labels differ - the preview says "would move"/"already organized".
		if (kind === "organization-execute" || kind === "organization-preview") {
			var isPreview = kind === "organization-preview";
			var moved = data.finished ? data.filesMoved : data.filesAnalyzed;
			document.getElementById("movedLabel").textContent = isPreview ? t("js.execution.wouldMove") : t("js.execution.moved");
			document.getElementById("skippedLabel").textContent = isPreview ? t("js.execution.alreadyOrganized") : t("js.execution.skipped");
			document.getElementById("movedCard").style.display = "";
			document.getElementById("skippedCard").style.display = "";
			document.getElementById("movedValue").textContent = moved != null ? moved : 0;
			document.getElementById("skippedValue").textContent = data.cacheHits != null ? data.cacheHits : 0;
		}

		var percent = data.percentComplete != null ? data.percentComplete : 0;

		document.getElementById("progressFill").style.width = percent + "%";
		var estimate = executionStatus.eta(data.eta);
		var message = data.message;
		document.getElementById("progressText").textContent = data.percentComplete != null
				? executionStatus.percent(percent) + " - " + (message || "")
				: (message || t("js.execution.processing"));
		if (estimate) document.getElementById("progressText").textContent += " · " + estimate;

		if (!data.finished) {
			return false;
		}

		document.getElementById("progressTitle").textContent = t("js.execution.completed");
		document.getElementById("progressFill").style.width = "100%";
		document.getElementById("viewResultLink").href = resultUrl(executionId, kind);
		document.getElementById("progressActions").style.display = "block";

		var cancelActions = document.getElementById("cancelActions");

		if (cancelActions) {
			cancelActions.style.display = "none";
		}

		return true;
	}

	function renderError(message) {
		document.getElementById("progressTitle").textContent = t("js.execution.status.error");
		document.getElementById("progressText").textContent = message || t("js.execution.loadError");

		var cancelActions = document.getElementById("cancelActions");

		if (cancelActions) {
			cancelActions.style.display = "none";
		}
	}

	function poll(executionId, kind) {
		if (document.hidden) {
			resumeOnVisible = function () { poll(executionId, kind); };

			return;
		}

		fetch("/api/executions/" + executionId)
				.then(function (response) {
					// A non-OK response (eg. 400 "Execution not found") is still valid JSON, so it
					// must be checked here - otherwise it would fall through to render() as if it
					// were progress data, never match a finished status, and poll forever.
					if (!response.ok) {
						return response.json()
								.catch(function () { return {}; })
								.then(function (body) {
									throw new Error(body && body.error ? body.error : "HTTP " + response.status);
								});
					}

					return response.json();
				})
				.then(function (data) {
					var finished = render(data, executionId, kind);

					if (!finished) {
						setTimeout(function () { poll(executionId, kind); }, 1500);
					}
				})
				.catch(function (error) {
					if (error instanceof TypeError) {
						// Network-level failure (offline, server restarting, etc.) - keep retrying.
						setTimeout(function () { poll(executionId, kind); }, 3000);
						return;
					}

					// The server responded but the execution is invalid/gone - stop polling.
					renderError(error.message);
				});
	}

	/**
	 * "Cancelar" asks the background thread to stop at its next checkpoint (next file / next
	 * item) - it isn't instantaneous, so the button just disables itself and leaves the regular
	 * poll() loop to pick up the CANCELLED status once the thread actually unwinds.
	 */
	function bindCancel(executionId) {
		var button = document.getElementById("cancelButton");
		var status = document.getElementById("cancelStatus");
		var token = document.getElementById("progressCsrfToken");

		if (!button) {
			return;
		}

		button.addEventListener("click", function () {
			if (!window.confirm(t("js.execution.cancelConfirm"))) {
				return;
			}

			button.disabled = true;
			button.textContent = t("js.execution.cancelling");

			fetch("/app/progress/" + executionId + "/cancel", {
				method: "POST",
				headers: { "X-CSRF-TOKEN": token ? token.value : "" }
			}).then(function (response) {
				return response.json();
			}).then(function (data) {
				if (data.requested) {
					if (status) {
						status.textContent = t("js.execution.cancelRequested");
					}
				} else {
					button.disabled = false;
					button.textContent = t("action.cancel");

					if (status) {
						status.textContent = t("js.execution.cancelFinished");
					}
				}
			}).catch(function () {
				button.disabled = false;
			button.textContent = t("action.cancel");

				if (status) {
					status.textContent = t("js.execution.cancelFailed");
				}
			});
		});
	}

	document.addEventListener("DOMContentLoaded", function () {
		var root = document.getElementById("progressRoot");

		if (!root) {
			return;
		}

		bindCancel(root.dataset.executionId);
		poll(root.dataset.executionId, root.dataset.kind);
	});
})();