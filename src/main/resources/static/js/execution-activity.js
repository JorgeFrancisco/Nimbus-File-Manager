/**
 * The banner that says what the machine is doing, on every authenticated page.
 *
 * It asks "what is active?" rather than following an execution it was told
 * about, and that difference is the whole point. The banner it replaces was
 * given one id when the page rendered: work that started a second later was
 * invisible until somebody reloaded, and when that one execution ended the
 * polling stopped, so whatever came next was invisible too.
 *
 * So this keeps asking even when the answer is "nothing" - that is how it
 * discovers work that has not started yet - and it never holds an id of its own.
 */
(function () {
	"use strict";

	/**
	 * Fast enough that clicking a button and watching the banner feels connected,
	 * slow enough that an idle machine is not asking four times a minute for an
	 * empty answer. The idle cadence is the one that runs most of the time.
	 */
	const ACTIVE_MILLIS = 2000;
	const IDLE_MILLIS = 6000;
	const HIDDEN_MILLIS = 20000;
	const ERROR_MILLIS = 10000;

	const banner = document.getElementById("executionActivity");
	const title = document.getElementById("executionActivityTitle");
	const detail = document.getElementById("executionActivityDetail");
	const status = document.getElementById("executionActivityStatus");
	const percent = document.getElementById("executionActivityPercent");
	const fill = document.getElementById("executionActivityProgressFill");
	const others = document.getElementById("executionActivityOthers");
	const progress = document.getElementById("executionActivityProgress");
	const stepPercent = document.getElementById("executionActivityStepPercent");
	const stepProgress = document.getElementById("executionActivityStepProgress");
	const stepFill = document.getElementById("executionActivityStepProgressFill");

	const i18n = window.NimbusFileManagerI18n;
	const executionStatus = window.NimbusFileManagerExecutionStatus;

	let polling = false;
	let timer = null;

	function render(snapshot) {
		const activity = snapshot && snapshot.primary;

		if (!activity) {
			banner.hidden = true;

			return IDLE_MILLIS;
		}

		banner.hidden = false;
		banner.href = activity.href;
		title.textContent = activity.typeLabel;
		status.textContent = activity.cancelRequested ? i18n.t("js.activity.cancelling") : activity.statusLabel;

		renderProgress(activity);
		renderDetail(activity);
		renderOthers(snapshot);

		return ACTIVE_MILLIS;
	}

	/**
	 * How many others, and - on hover - which ones. "Who is ahead of my geo dataset
	 * update?" was a real question with no answer on any screen, and the queue
	 * already knows: these arrive in the order the worker would take them.
	 */
	function renderOthers(snapshot) {
		const remaining = snapshot.totalActive - 1;

		others.hidden = remaining < 1;
		others.textContent = remaining < 1 ? "" : i18n.t("js.activity.others", remaining);
		others.title = (snapshot.others || []).map(function (activity) {
			return activity.typeLabel + " - " + activity.statusLabel;
		}).join("\n");
	}

	/**
	 * A bar only when the work can honestly measure itself. Types whose progress
	 * has no denominator - a reconcile, a purge - say what they are doing and show
	 * no percentage, rather than a bar frozen at zero that reads as stuck.
	 */
	function renderProgress(activity) {
		const measurable = has(activity.percentComplete);

		progress.hidden = !measurable;
		percent.hidden = !measurable;

		if (measurable) {
			percent.textContent = executionStatus.percent(activity.percentComplete);
			fill.style.width = activity.percentComplete + "%";
		}

		renderStep(activity);
	}

	/**
	 * The second bar: how far into the step being worked on right now. Counting
	 * finished items is not the same as being finished - a geodata update that has
	 * imported all three administrative levels reads 100% while it is still
	 * writing the supplemental files, and the overall bar has no way to say so.
	 *
	 * Shown only when the work reports one, so nothing that has a single level of
	 * progress grows a second empty bar.
	 */
	function renderStep(activity) {
		const reported = has(activity.currentItemPercent);

		stepProgress.hidden = !reported;
		stepPercent.hidden = !reported;

		if (reported) {
			stepPercent.textContent = i18n.t("js.activity.currentStep",
					executionStatus.percent(activity.currentItemPercent));
			stepFill.style.width = activity.currentItemPercent + "%";
		}
	}

	function has(value) {
		return value !== null && value !== undefined;
	}

	function renderDetail(activity) {
		if (activity.totalExpected) {
			detail.textContent = i18n.t("js.activity.counts", activity.filesFound || 0, activity.totalExpected);
		} else {
			detail.textContent = activity.sourcePath || "";
		}
	}

	function schedule(wait) {
		window.clearTimeout(timer);

		timer = window.setTimeout(poll, document.hidden ? HIDDEN_MILLIS : wait);
	}

	function poll() {
		// One request at a time: a slow answer must not leave a queue of them behind
		// it, which is how a polling banner turns into load.
		if (polling) {
			return;
		}

		polling = true;

		fetch("/api/execution-activity")
				.then(function (response) {
					if (!response.ok) {
						throw new Error("HTTP " + response.status);
					}

					return response.json();
				})
				.then(function (snapshot) {
					schedule(render(snapshot));
				})
				.catch(function () {
					// A failed poll says nothing about the work. The banner keeps whatever it
					// was showing and the asking continues, more slowly.
					schedule(ERROR_MILLIS);
				})
				.finally(function () {
					polling = false;
				});
	}

	// Pages outside the app shell - login, the error page - load none of this, and
	// nothing below should run without somewhere to draw.
	if (!banner) {
		return;
	}

	// Coming back to the tab is the moment the answer is most likely to be stale.
	document.addEventListener("visibilitychange", function () {
		if (!document.hidden) {
			schedule(0);
		}
	});

	// Straight away, not on DOMContentLoaded: the banner element is above this
	// script in the shell, so it already exists, and every flow that queues work
	// reloads or navigates - which makes "the page just opened" the moment the
	// answer matters most.
	poll();
})();