/**
 * Drives the Conversão screen: the checkbox selection, the three conversion options and the
 * background batch (POST /app/conversion/convert -> H.265 MP4).
 *
 * The selection is persisted in localStorage so it survives pagination - only one page of
 * candidates is rendered at a time, and converting acts on everything the user picked, not just
 * on what is currently on screen. The options are pushed to the server the moment they change,
 * so they are remembered per user even when no conversion is started.
 */
(function () {
	"use strict";

	const i18n = window.NimbusFileManagerI18n;
	const t = i18n.t.bind(i18n);

	const executionStatus = window.NimbusFileManagerExecutionStatus;

	const optionsForm = document.getElementById('conversionOptions');
	const convertButton = document.getElementById('conversionConvert');
	const clearButton = document.getElementById('conversionClearSelection');
	const cancelButton = document.getElementById('conversionCancel');
	const selectAll = document.getElementById('conversionSelectAll');
	const selectedCount = document.getElementById('conversionSelectedCount');
	const confirmDialog = document.getElementById('conversionConfirmDialog');
	const confirmCount = document.getElementById('conversionConfirmCount');
	const progressBar = document.getElementById('conversionProgress');
	const progressFill = document.getElementById('conversionProgressFill');
	const progressText = document.getElementById('conversionProgressText');
	const report = document.getElementById('conversionReport');
	const reportBody = document.getElementById('conversionReportBody');
	const labels = document.getElementById('conversionOutcomeLabels');
	const status = document.getElementById('conversionStatus') || document.getElementById('conversionStatusIdle');
	const pagination = document.getElementById('conversionPagination');
	const pageSize = document.getElementById('conversionPageSize');
	const candidates = document.getElementById('conversionCandidates');

	const checkboxes = Array.prototype.slice.call(document.querySelectorAll('.js-select'));

	// The batch runs for minutes, so the screen polls instead of waiting on the POST.
	const POLL_INTERVAL_MILLIS = 1500;
	const POLL_RETRY_MILLIS = 3000;
	const MAX_POLL_FAILURES = 10;
	const RELOAD_DELAY_MILLIS = 1200;

	let pollFailures = 0;

	// ---- Cross-page selection store (localStorage) -----------------------------
	const SELECTION_KEY = 'mm-conversion-selection';
	const REPORT_KEY = 'mm-conversion-report';

	function loadSelection() {
		try {
			const raw = JSON.parse(window.localStorage.getItem(SELECTION_KEY) || '{}');

			return raw && typeof raw === 'object' ? raw : {};
		} catch (error) {
			return {};
		}
	}

	// id -> sizeBytes, so the selection survives pagination and a reload.
	const selection = loadSelection();

	function saveSelection() {
		try {
			window.localStorage.setItem(SELECTION_KEY, JSON.stringify(selection));
		} catch (error) {
			// Storage unavailable/full: keep the in-memory selection, just skip persisting.
		}
	}

	function selectedIds() {
		return Object.keys(selection);
	}

	function csrfToken() {
		const csrf = document.querySelector("input[name='_csrf']");

		return csrf ? csrf.value : '';
	}

	// A batch already owns every file it was given, so nothing may be selected or
	// re-configured until it ends - the server refuses a second batch anyway, and a
	// half-changed screen would only look like it accepted the change.
	let running = optionsForm ? optionsForm.dataset.running === 'true' : false;

	function setRunning(value) {
		running = value;

		checkboxes.forEach((checkbox) => {
			checkbox.disabled = value;
		});

		Array.prototype.slice.call(optionsForm ? optionsForm.elements : []).forEach((field) => {
			field.disabled = value;
		});

		if (selectAll) {
			selectAll.disabled = value;
		}

		if (cancelButton) {
			cancelButton.hidden = !value;
			cancelButton.disabled = false;
		}

		setPagingBlocked(value);
		setPreviewBlocked(value);

		updateSelection();
	}

	// Opening a candidate mid-batch means playing a file that is queued to move into
	// quarantine: the player freezes the moment it does, and the user has no way of
	// knowing which file is next in line. The card stays visible, only inert.
	function setPreviewBlocked(value) {
		if (!candidates) {
			return;
		}

		candidates.classList.toggle('preview-blocked', value);

		Array.prototype.slice.call(candidates.querySelectorAll('.media-card-open')).forEach((card) => {
			card.setAttribute('aria-disabled', String(value));
		});
	}

	// Leaving the page mid-batch loses the progress and the final report, and the
	// list itself is about to change under the user - every converted file stops
	// being a candidate. The server re-renders the page blocked when a batch is
	// already running; this keeps it blocked when the batch starts right here.
	function setPagingBlocked(value) {
		if (pageSize) {
			pageSize.disabled = value;
		}

		if (!pagination) {
			return;
		}

		pagination.title = value ? pagination.dataset.blockedTitle || '' : '';

		Array.prototype.slice.call(pagination.querySelectorAll('a')).forEach((link) => {
			link.classList.toggle('disabled', value);
			link.setAttribute('aria-disabled', String(value));
		});
	}

	function updateSelection() {
		const count = selectedIds().length;

		if (selectedCount) {
			selectedCount.textContent = String(count);
		}

		if (convertButton) {
			convertButton.disabled = running || count === 0;
		}

		if (clearButton) {
			clearButton.disabled = running || count === 0;
		}

		if (selectAll) {
			selectAll.checked = checkboxes.length > 0 && checkboxes.every((checkbox) => checkbox.checked);
		}
	}

	function syncCheckbox(checkbox) {
		const id = checkbox.dataset.mediaId;

		if (!id) {
			return;
		}

		if (checkbox.checked) {
			selection[id] = Number(checkbox.dataset.sizeBytes || 0);
		} else {
			delete selection[id];
		}
	}

	function applyStoredSelection() {
		checkboxes.forEach((checkbox) => {
			checkbox.checked = Object.prototype.hasOwnProperty.call(selection, checkbox.dataset.mediaId);
		});
	}

	function forgetHandled(ids) {
		ids.forEach((id) => delete selection[id]);
		saveSelection();
	}

	function setStatus(message, isError) {
		if (!status) {
			return;
		}

		status.hidden = !message;
		status.textContent = message || '';
		status.classList.toggle('error-text', Boolean(isError));
	}

	function setProgress(percent) {
		if (!progressBar || !progressFill) {
			return;
		}

		if (percent === null) {
			progressBar.hidden = true;

			if (progressText) {
				progressText.hidden = true;
			}

			return;
		}

		const clamped = Math.max(0, Math.min(100, percent));

		progressBar.hidden = false;
		progressBar.setAttribute('aria-valuenow', String(clamped));
		progressFill.style.width = `${clamped}%`;
	}

	// Percentage plus time remaining, the same shape (and the same js.eta.* wording)
	// every other long-running screen in the app shows.
	function setProgressText(progress) {
		if (!progressText) {
			return;
		}

		const parts = [executionStatus.percent(progress.percent || 0)];

		if (progress.etaSeconds >= 0) {
			parts.push(executionStatus.format(progress.etaSeconds));
		} else {
			parts.push(t('js.conversion.etaCalculating'));
		}

		if (progress.currentFile) {
			parts.push(t('js.conversion.currentFile', progress.currentFile, progress.filePercent || 0));
		}

		progressText.hidden = false;
		progressText.textContent = parts.join(' · ');
	}

	// Read the controls directly instead of serialising the form: every field is
	// disabled while a batch runs, and a disabled field is left out of FormData, so
	// the options travelled as nulls and the server replaced each one with its
	// default - silently turning the user's saved choices back to the recommended
	// combination the moment they pressed convert.
	function pickedValue(name) {
		const option = optionsForm.querySelector('input[name="' + name + '"]:checked');

		return option ? option.value : null;
	}

	function chosenOptions() {
		const affix = optionsForm.querySelector('input[name="nameAffix"]');

		return {
			quality: pickedValue('quality'),
			audio: pickedValue('audio'),
			disposition: pickedValue('disposition'),
			nameAffix: affix ? affix.value : '',
			affixPosition: pickedValue('affixPosition')
		};
	}

	// The options belong to the user, not to a batch, so they are stored the moment
	// they change - leaving the screen never discards the choice.
	function rememberOptions() {
		fetch('/app/conversion/preferences', {
			method: 'POST',
			headers: {
				'Content-Type': 'application/json',
				'X-CSRF-TOKEN': csrfToken()
			},
			body: JSON.stringify(chosenOptions())
		}).catch(() => {
			// A preference that could not be stored is not worth interrupting the user
			// over: the conversion still uses what is selected on screen.
		});
	}

	// The outcome arrives as a code; the wording comes from the server-rendered
	// labels, so the screen never translates a domain value itself.
	function outcomeLabel(outcome) {
		if (outcome === 'CONVERTED') {
			return labels.dataset.converted;
		}

		if (outcome === 'SKIPPED') {
			return labels.dataset.skipped;
		}

		return labels.dataset.failed;
	}

	function outcomeBadgeClass(outcome) {
		if (outcome === 'CONVERTED') {
			return 'ok';
		}

		return outcome === 'SKIPPED' ? 'muted' : 'error';
	}

	function renderReport(result) {
		if (!result || !reportBody) {
			return;
		}

		reportBody.innerHTML = '';

		(result.items || []).forEach((item) => {
			const row = document.createElement('tr');

			const file = document.createElement('td');
			file.textContent = item.fileName;

			const outcome = document.createElement('td');
			const badge = document.createElement('span');
			badge.className = `badge ${outcomeBadgeClass(item.outcome)}`;
			badge.textContent = outcomeLabel(item.outcome);
			outcome.appendChild(badge);

			const saved = document.createElement('td');
			saved.textContent = item.outcome === 'CONVERTED' ? item.savedLabel : '—';

			const detail = document.createElement('td');
			detail.textContent = [
				item.message,
				item.adjustments.audioFallback ? labels.dataset.audioFallback : '',
				item.adjustments.subtitlesDropped ? labels.dataset.subtitlesDropped : '',
				item.adjustments.dataDropped ? labels.dataset.dataDropped : '',
				item.originalQuarantined ? labels.dataset.quarantined : ''
			].filter(Boolean).join(' · ');

			row.append(file, outcome, saved, detail);
			reportBody.appendChild(row);
		});

		report.hidden = (result.items || []).length === 0;
	}

	function showOutcome(result) {
		renderReport(result);

		if (!result.configured || result.errors > 0) {
			setStatus(result.message, true);
			return;
		}

		setStatus(t('js.conversion.done', result.converted, result.savedLabel || '0 B'), false);
	}

	// The report has to outlive the reload below, so it travels in session storage
	// and is shown again as soon as the fresh page comes up.
	function rememberReport(result) {
		try {
			window.sessionStorage.setItem(REPORT_KEY, JSON.stringify(result));
		} catch (error) {
			// Storage unavailable: the page simply comes back without the report.
		}
	}

	function restoreReport() {
		let stored = null;

		try {
			stored = window.sessionStorage.getItem(REPORT_KEY);
			window.sessionStorage.removeItem(REPORT_KEY);
		} catch (error) {
			return;
		}

		if (!stored) {
			return;
		}

		try {
			showOutcome(JSON.parse(stored));
		} catch (error) {
			// Leftover from an older format: there is nothing worth showing.
		}
	}

	function finish(result) {
		setProgress(null);
		setRunning(false);

		if (!result) {
			updateSelection();
			setStatus('', false);
			return;
		}

		// Whatever the batch dealt with is no longer pending, so it leaves the stored
		// selection - a failed file included, since retrying it is a fresh decision.
		forgetHandled((result.items || []).map((item) => item.mediaId));
		applyStoredSelection();
		updateSelection();

		showOutcome(result);

		// A converted file stops being a candidate, and the count, the pages and the
		// rows around it all change with it. Reloading is what makes the whole screen
		// agree again - before this, the files just converted stayed on the list until
		// the user reloaded by hand.
		rememberReport(result);
		window.setTimeout(() => window.location.reload(), RELOAD_DELAY_MILLIS);
	}

	function handleProgress(progress) {
		pollFailures = 0;

		if (progress && progress.running) {
			setRunning(true);
			setStatus(t('js.conversion.progress', progress.processed, progress.total, executionStatus.percent(progress.percent || 0)), false);
			setProgress(progress.percent || 0);
			setProgressText(progress);
			window.setTimeout(pollProgress, POLL_INTERVAL_MILLIS);

			return;
		}

		finish(progress ? progress.result : null);
	}

	function pollProgress() {
		fetch('/app/conversion/progress')
			.then((response) => {
				if (!response.ok) {
					throw new Error(`HTTP ${response.status}`);
				}

				return response.json();
			})
			.then(handleProgress)
			.catch(() => {
				pollFailures += 1;

				// One failed poll is not the batch failing - it keeps converting on the
				// server. Giving up here froze the screen for the rest of the run: no final
				// report and no refreshed list, which is exactly what happened while five
				// full inventories were competing with a 48-minute batch.
				if (pollFailures <= MAX_POLL_FAILURES) {
					window.setTimeout(pollProgress, POLL_RETRY_MILLIS);
					return;
				}

				setStatus(t('js.conversion.progressError'), true);
				setProgress(null);
				setRunning(false);
			});
	}

	function startConversion(ids) {
		setRunning(true);
		setStatus(t('js.conversion.starting'), false);
		setProgress(0);

		fetch('/app/conversion/convert', {
			method: 'POST',
			headers: {
				'Content-Type': 'application/json',
				'X-CSRF-TOKEN': csrfToken()
			},
			body: JSON.stringify({ ids, ...chosenOptions() })
		})
			.then((response) => {
				if (!response.ok) {
					throw new Error(`HTTP ${response.status}`);
				}

				return response.json();
			})
			.then(handleProgress)
			.catch(() => {
				setStatus(t('js.conversion.error'), true);
				setProgress(null);
				setRunning(false);
			});
	}

	function cancelConversion() {
		cancelButton.disabled = true;
		setStatus(t('js.conversion.cancelling'), false);

		fetch('/app/conversion/cancel', {
			method: 'POST',
			headers: {
				'Content-Type': 'application/json',
				'X-CSRF-TOKEN': csrfToken()
			}
		})
			.then((response) => {
				if (!response.ok) {
					throw new Error(`HTTP ${response.status}`);
				}

				return response.json();
			})
			.then(handleProgress)
			.catch(() => setStatus(t('js.conversion.cancelError'), true));
	}

	checkboxes.forEach((checkbox) => checkbox.addEventListener('change', () => {
		syncCheckbox(checkbox);
		saveSelection();
		updateSelection();
	}));

	if (selectAll) {
		selectAll.addEventListener('change', () => {
			checkboxes.forEach((checkbox) => {
				checkbox.checked = selectAll.checked;
				syncCheckbox(checkbox);
			});

			saveSelection();
			updateSelection();
		});
	}

	if (clearButton) {
		clearButton.addEventListener('click', () => {
			forgetHandled(selectedIds());
			applyStoredSelection();
			updateSelection();
		});
	}

	if (optionsForm) {
		optionsForm.addEventListener('change', rememberOptions);
	}

	if (pagination) {
		// The disabled class stops the mouse (pointer-events), not a focused link
		// activated with the keyboard, so the click itself is refused here too.
		pagination.addEventListener('click', (event) => {
			if (running) {
				event.preventDefault();
			}
		});
	}

	if (candidates) {
		// Capture phase on purpose: the lightbox listens on document, so the click has
		// to be stopped on the way down or it opens anyway.
		candidates.addEventListener('click', (event) => {
			if (!running || !event.target.closest('.media-card-open')) {
				return;
			}

			event.preventDefault();
			event.stopPropagation();

			setStatus(candidates.dataset.blockedTitle || '', false);
		}, true);
	}

	if (cancelButton) {
		cancelButton.addEventListener('click', cancelConversion);
	}

	if (convertButton && confirmDialog) {
		convertButton.addEventListener('click', () => {
			const ids = selectedIds();

			if (ids.length === 0) {
				setStatus(t('js.conversion.noneSelected'), true);
				return;
			}

			confirmCount.textContent = String(ids.length);
			confirmDialog.showModal();
		});

		confirmDialog.querySelector('.js-conversion-cancel').addEventListener('click', () => confirmDialog.close());

		confirmDialog.querySelector('.js-conversion-confirm').addEventListener('click', () => {
			confirmDialog.close();
			startConversion(selectedIds());
		});
	}

	// The stored selection may name files a batch already converted while this
	// screen was closed - they are no longer candidates, and counting them told the
	// user a page of 300 had 600 selected. The server decides which ones survive.
	function pruneStoredSelection() {
		const stored = selectedIds();

		if (stored.length === 0) {
			return;
		}

		fetch('/app/conversion/selection', {
			method: 'POST',
			headers: {
				'Content-Type': 'application/json',
				'X-CSRF-TOKEN': csrfToken()
			},
			body: JSON.stringify({ ids: stored })
		})
			.then((response) => (response.ok ? response.json() : null))
			.then((alive) => {
				if (!alive) {
					return;
				}

				const keep = new Set(alive);

				forgetHandled(stored.filter((id) => !keep.has(id)));
				applyStoredSelection();
				updateSelection();
			})
			// Offline or refused: keep what is stored rather than dropping a real selection.
			.catch(() => {});
	}

	applyStoredSelection();
	setRunning(running);
	restoreReport();
	pruneStoredSelection();

	// A conversion started before this page was opened (or on another tab) keeps
	// being followed here instead of looking finished.
	if (running) {
		pollProgress();
	}
})();