/**
 * Client-side live ETA estimator for a running execution. Both active-execution.js (sidebar
 * widget) and pages/execution-progress.js (full progress page) poll the same execution and need
 * the same "estimated time remaining" number, so it is computed once here over a shared rolling
 * sample window and read by both. Status classification (terminal or not), the status label and
 * the progress message are authored and localized by the backend (ExecutionResponse.finished /
 * statusLabel / message) and read straight off the polled data object by the consumers - this
 * module only owns the visual ETA, which uses the js.eta.* keys.
 */
window.NimbusFileManagerExecutionStatus = (function () {
	var t = window.NimbusFileManagerI18n.t;

	// Rolling window of (timestamp, filesFound) samples for a RECENT-rate ETA. A plain
	// cumulative average by file count is "off" because per-file cost varies wildly (a
	// 2 GB video read for hashing costs ~1000x a small photo) and because it carries the
	// cold-start/scan warm-up. The recent window adapts to the current file mix - big
	// files slow the rate and push the ETA up, small files pull it down - and ignores the
	// warm-up. It is reactive, not predictive: it reacts once throughput changes rather
	// than foreseeing that large files are still ahead.
	var WINDOW_MS = 25000;
	var MIN_SPAN_MS = 6000;
	var samples = [];
	var currentKey = null;

	function recordSample(key, files, now) {
		if (key !== currentKey) {
			currentKey = key;
			samples = [];
		}

		var last = samples.length ? samples[samples.length - 1] : null;

		if (last && files < last.files) {
			samples = [];
			last = null;
		}

		// Dedupe rapid identical polls (both the sidebar and the progress page can call this).
		if (last && files === last.files && (now - last.t) < 1000) {
			return;
		}

		samples.push({ t: now, files: files });

		var cutoff = now - WINDOW_MS;
		while (samples.length > 2 && samples[0].t < cutoff) {
			samples.shift();
		}
	}

	function recentFilesPerSecond(now) {
		if (samples.length < 2) {
			return null;
		}

		var oldest = samples[0];
		var spanMs = now - oldest.t;
		var deltaFiles = samples[samples.length - 1].files - oldest.files;

		if (spanMs < MIN_SPAN_MS || deltaFiles <= 0) {
			return null;
		}

		return deltaFiles / (spanMs / 1000);
	}

	function humanize(remainingSeconds) {
		if (remainingSeconds < 60) return t("js.eta.lessThanMinute");
		var minutes = Math.round(remainingSeconds / 60);
		if (minutes < 60) return t("js.eta.minutes", minutes);
		var hours = Math.floor(minutes / 60), rest = minutes % 60;
		return t("js.eta.hours", hours, rest ? " " + rest + " min" : "");
	}

	return {
		estimatedRemaining: function (data) {
			if (!data || data.status !== "PROCESSING_FILES" || !data.startedAt || !data.totalExpected
					|| !data.filesFound || data.filesFound < 10 || data.filesFound >= data.totalExpected) return null;

			var now = Date.now();
			recordSample(String(data.startedAt), data.filesFound, now);

			var remainingFiles = data.totalExpected - data.filesFound;
			var rate = recentFilesPerSecond(now);
			var remainingSeconds;

			if (rate && rate > 0) {
				remainingSeconds = Math.round(remainingFiles / rate);
			} else {
				// Warm-up fallback: cumulative average until the recent window has enough span.
				var elapsedSeconds = (now - new Date(data.startedAt).getTime()) / 1000;
				if (!Number.isFinite(elapsedSeconds) || elapsedSeconds < 10) return null;
				remainingSeconds = Math.round(elapsedSeconds * remainingFiles / data.filesFound);
			}

			return humanize(remainingSeconds);
		}
	};
})();