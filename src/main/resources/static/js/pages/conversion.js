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

	const checkboxes = Array.prototype.slice.call(document.querySelectorAll('.js-select'));

	// The batch runs for minutes, so the screen polls instead of waiting on the POST.
	const POLL_INTERVAL_MILLIS = 1500;

	// ---- Cross-page selection store (localStorage) -----------------------------
	const SELECTION_KEY = 'mm-conversion-selection';

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

		updateSelection();
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

		const parts = [`${progress.percent || 0}%`];

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

	function chosenOptions() {
		const data = new FormData(optionsForm);

		return {
			quality: data.get('quality'),
			audio: data.get('audio'),
			disposition: data.get('disposition'),
			nameAffix: data.get('nameAffix') || '',
			affixPosition: data.get('affixPosition')
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
				item.audioFallback ? labels.dataset.audioFallback : '',
				item.subtitlesDropped ? labels.dataset.subtitlesDropped : '',
				item.originalQuarantined ? labels.dataset.quarantined : ''
			].filter(Boolean).join(' · ');

			row.append(file, outcome, saved, detail);
			reportBody.appendChild(row);
		});

		report.hidden = (result.items || []).length === 0;
	}

	function finish(result) {
		setProgress(null);
		setRunning(false);

		if (!result) {
			updateSelection();
			setStatus('', false);
			return;
		}

		renderReport(result);

		// Whatever the batch dealt with is no longer pending, so it leaves the stored
		// selection - a failed file included, since retrying it is a fresh decision.
		forgetHandled((result.items || []).map((item) => item.mediaId));
		applyStoredSelection();
		updateSelection();

		if (!result.configured || result.errors > 0) {
			setStatus(result.message, true);
			return;
		}

		setStatus(t('js.conversion.done', result.converted, result.savedLabel || '0 B'), false);
	}

	function handleProgress(progress) {
		if (progress && progress.running) {
			setRunning(true);
			setStatus(t('js.conversion.progress', progress.processed, progress.total, progress.percent || 0), false);
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

	applyStoredSelection();
	setRunning(running);

	// A conversion started before this page was opened (or on another tab) keeps
	// being followed here instead of looking finished.
	if (running) {
		pollProgress();
	}
})();