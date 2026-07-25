(function () {
	var executionStatus = window.NimbusFileManagerExecutionStatus;
	var t = window.NimbusFileManagerI18n.t;

	function render(root, data) {
		var percent = data.percentComplete != null ? data.percentComplete : 0;
		var status = document.getElementById("activeExecutionStatus");
		var percentLabel = document.getElementById("activeExecutionPercent");
		var fill = document.getElementById("activeExecutionProgressFill");
		var folder = document.getElementById("activeExecutionFolder");

		status.textContent = data.statusLabel;
		var estimate = executionStatus.estimatedRemaining(data);
		percentLabel.textContent = data.percentComplete != null
				? t("js.active.progress", percent.toFixed(1), data.filesFound || 0, data.totalExpected)
				: t("js.active.preparing");
		if (estimate) percentLabel.textContent += " · " + estimate;
		fill.style.width = percent + "%";

		if (folder && data.sourcePath) {
			folder.title = data.sourcePath;
			folder.querySelector(".path").textContent = data.sourcePath;
		}

		if (data.finished) {
			root.classList.add("completed");
			document.getElementById("activeExecutionTitle").textContent = t("js.active.finished");
			fill.style.width = "100%";
			document.dispatchEvent(new CustomEvent("nimbus-file-manager:execution-finished", { detail: data }));
			return true;
		}

		return false;
	}

	function poll(root) {
		if (document.hidden) {
			setTimeout(function () { poll(root); }, 2000);
			return;
		}

		fetch("/api/executions/" + root.dataset.executionId)
				.then(function (response) {
					if (!response.ok) {
						throw new Error("HTTP " + response.status);
					}
					return response.json();
				})
				.then(function (data) {
					if (!render(root, data)) {
						setTimeout(function () { poll(root); }, 1500);
					}
				})
				.catch(function () {
					setTimeout(function () { poll(root); }, 3000);
				});
	}

	document.addEventListener("DOMContentLoaded", function () {
		var root = document.getElementById("activeExecution");
		if (root) {
			poll(root);
		}
	});
})();