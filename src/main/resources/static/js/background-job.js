/**
 * Keeps the background-job banner honest between page loads.
 *
 * Unlike the execution banner, this one polls even when nothing is running: the
 * fingerprint backlogs start on their own - after an inventory, after a
 * conversion ends - and the whole point is that the user should not have to
 * reload a page to discover the machine is busy.
 */
(function () {
	"use strict";

	const POLL_MILLIS = 5000;
	const HIDDEN_POLL_MILLIS = 15000;

	const banner = document.getElementById("backgroundJob");
	const label = document.getElementById("backgroundJobLabel");
	const count = document.getElementById("backgroundJobCount");
	const percent = document.getElementById("backgroundJobPercent");
	const fill = document.getElementById("backgroundJobProgressFill");
	const i18n = window.NimbusFileManagerI18n;

	function render(job) {
		if (!job) {
			banner.hidden = true;

			return;
		}

		banner.hidden = false;
		banner.href = job.link;
		label.textContent = job.label;
		count.textContent = i18n.t("js.backgroundJob.count", job.processed, job.total);
		percent.textContent = job.percent + "%";
		fill.style.width = job.percent + "%";
	}

	function poll() {
		const wait = document.hidden ? HIDDEN_POLL_MILLIS : POLL_MILLIS;

		fetch("/api/background-job")
				.then(function (response) {
					// A 204 (nothing running) has no body to parse.
					return response.ok && response.status !== 204 ? response.json() : null;
				})
				.then(render)
				.catch(function () {
					// A failed poll says nothing about the job; keep the banner as it is.
				})
				.finally(function () {
					setTimeout(poll, wait);
				});
	}

	document.addEventListener("DOMContentLoaded", function () {
		if (banner) {
			setTimeout(poll, POLL_MILLIS);
		}
	});
})();