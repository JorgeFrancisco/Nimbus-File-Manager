/**
 * How an execution's numbers read on screen: the remaining time and the percentage. Both arrive
 * from the backend already decided - the seconds are measured and rounded there, over the window
 * and the progress model the workload declares - and this module only puts them into the reader's
 * language.
 *
 * This used to estimate the remaining time itself, from a rolling window of its own over
 * the discovery counter. It could not: that field means the discovery count in an inventory, the
 * concluded count in a fingerprint, the *total* in a similarity analysis and a constant zero in a
 * metadata rebuild, so one reading of it was wrong for three workloads at once - and the number it
 * produced disagreed with the one the duplicates panel showed for the same run. Rate is measured in
 * exactly one place now, and it is not here.
 */
window.NimbusFileManagerExecutionStatus = (function () {
	var t = window.NimbusFileManagerI18n.t;

	// The seconds arrive already rounded to the precision the measurement supports, so this never
	// rounds again: a second rounding would either claim precision back or lose a band.
	function humanize(remainingSeconds) {
		if (remainingSeconds < 60) return t("eta.lessThanMinute");

		var minutes = Math.round(remainingSeconds / 60);

		if (minutes < 60) return t("eta.minutes", minutes);

		var hours = Math.floor(minutes / 60), rest = minutes % 60;

		return rest ? t("eta.hoursMinutes", hours, rest) : t("eta.hours", hours);
	}

	/**
	 * The one sentence for a remaining time, from the state the backend reports. AVAILABLE carries
	 * a number; CALCULATING says so; NOT_APPLICABLE says nothing at all, because work with no
	 * honest denominator has no estimate to wait for and "calculating…" would promise one forever.
	 */
	function etaText(eta) {
		if (!eta || eta.state === "NOT_APPLICABLE") return "";

		if (eta.state === "CALCULATING") return t("eta.calculating");

		return humanize(eta.remainingSeconds);
	}

	// Percentages carry two decimals, and the separator is the reader's, not Java's:
	// "97,88%" in pt-BR, "97.88%" in English. Only the visible text goes through here -
	// a CSS width must stay a raw number with a dot, or the bar silently breaks.
	var percentFormat = new Intl.NumberFormat(window.NimbusFileManagerI18n.locale, {
		style: "percent",
		minimumFractionDigits: 2,
		maximumFractionDigits: 2
	});

	function percentText(value) {
		var number = Number(value);

		// style:"percent" wants a fraction, and it owns the sign: where it goes and
		// whether a space precedes it differ by language, so no caller appends "%".
		return Number.isFinite(number) ? percentFormat.format(number / 100) : "";
	}

	return {
		// Shared by every screen that shows "time remaining", so the wording stays the same
		// wherever the estimate is displayed.
		format: humanize,

		eta: etaText,

		// Shared for the same reason: one place decides how a percentage reads.
		percent: percentText
	};
})();