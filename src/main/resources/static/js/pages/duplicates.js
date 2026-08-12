/**
 * Drives the checkbox selection on the Duplicados screen (Exatos and Fotos semelhantes tabs):
 * the "what would be deleted" preview, the per-group/per-folder bulk marks, and the real
 * deletion (POST /app/duplicates/delete -> move to quarantine).
 *
 * The selection is persisted in localStorage (keyed per tab) so it survives pagination: each
 * page only renders 20 groups, so the selected ids/sizes are kept across pages and the
 * summary/count reflect the whole selection, not just the current page. A deletion removes the
 * moved ids from that store.
 */
document.addEventListener('DOMContentLoaded', () => {
	const t = window.NimbusFileManagerI18n.t;
	const checkboxes = Array.from(document.querySelectorAll('.js-delete-checkbox'));
	const groupToggles = Array.from(document.querySelectorAll('.js-select-group'));
	const folderButtons = Array.from(document.querySelectorAll('.js-select-folder'));

	// Type filter: each checkbox re-renders the page via its GET form. Unchecking
	// every type is treated as "show all", so re-check them all before submitting
	// (the server would otherwise read an empty submit as the previous saved filter).
	const typeChecks = Array.from(document.querySelectorAll('.js-duplicates-type'));
	typeChecks.forEach((box) => box.addEventListener('change', () => {
		if (!typeChecks.some((other) => other.checked)) {
			typeChecks.forEach((other) => { other.checked = true; });
		}
		box.form.submit();
	}));
	const selectionCount = document.getElementById('selectionCount');
	const selectionSize = document.getElementById('selectionSize');
	const selectionBar = document.getElementById('selectionBar');

	// Decided by the server and rendered into the bar: a conversion holds the
	// quarantine a deletion writes to. The screen only obeys - it never works out
	// on its own whether deleting is allowed.
	const deletionBlocked = selectionBar ? selectionBar.dataset.deletionBlocked === 'true' : false;

	const previewButton = document.getElementById('previewDeleteButton');
	const suggestSelectionButton = document.getElementById('suggestSelectionButton');
	const clearSelectionButton = document.getElementById('clearSelectionButton');
	const previewPanel = document.getElementById('deletePreviewPanel');
	const previewList = document.getElementById('deletePreviewList');
	const confirmDeleteButton = document.getElementById('confirmDeleteButton');
	const deleteStatus = document.getElementById('deleteStatus');
	const deleteProgressBar = document.getElementById('deleteProgressBar');
	const deleteProgressFill = document.getElementById('deleteProgressFill');
	const deleteConfirmDialog = document.getElementById('deleteConfirmDialog');
	const deleteConfirmCount = document.getElementById('deleteConfirmCount');
	const folderMarkDialog = document.getElementById('folderMarkDialog');
	const folderMarkPath = document.getElementById('folderMarkPath');
	const failuresDialog = document.getElementById('fingerprintFailuresDialog');
	const failuresStatus = document.getElementById('fingerprintFailuresStatus');
	const failuresTableWrap = document.getElementById('fingerprintFailuresTableWrap');
	const failuresRows = document.getElementById('fingerprintFailuresRows');
	let failuresLoaded = false;

	// ---- Cross-page selection store (localStorage, per tab) --------------------
	const selectionTab = new URLSearchParams(window.location.search).get('tab') || 'exact';
	const SELECTION_KEY = 'mm-duplicates-selection-' + selectionTab;

	function loadSelection() {
		try {
			const raw = JSON.parse(window.localStorage.getItem(SELECTION_KEY) || '{}');
			return { selected: raw.selected || {}, deselected: new Set(raw.deselected || []) };
		} catch (error) {
			return { selected: {}, deselected: new Set() };
		}
	}

	// selected: id -> sizeBytes (marked for deletion). deselected: ids a candidate default was
	// explicitly turned off for, so revisiting a page never re-checks them.
	const selection = loadSelection();

	function saveSelection() {
		try {
			window.localStorage.setItem(SELECTION_KEY,
				JSON.stringify({ selected: selection.selected, deselected: Array.from(selection.deselected) }));
		} catch (error) {
			// Storage unavailable/full: keep the in-memory selection, just skip persisting.
		}
	}

	function syncCheckbox(checkbox) {
		const id = checkbox.dataset.fileId;
		if (!id) {
			return;
		}

		if (checkbox.checked) {
			selection.selected[id] = Number(checkbox.dataset.sizeBytes || 0);
			selection.deselected.delete(id);
		} else {
			delete selection.selected[id];
			selection.deselected.add(id);
		}
	}

	function selectedIds() {
		return Object.keys(selection.selected);
	}

	function formatBytes(bytes) {
		const units = ['B', 'KB', 'MB', 'GB', 'TB'];
		let value = bytes;
		let unitIndex = 0;

		while (value >= 1024 && unitIndex < units.length - 1) {
			value /= 1024;
			unitIndex += 1;
		}

		return `${value.toFixed(unitIndex === 0 ? 0 : 1)} ${units[unitIndex]}`;
	}

	function selectedCheckboxes() {
		return checkboxes.filter((checkbox) => checkbox.checked);
	}

	// Single source of truth shared by "Sugerir seleção" and every bulk-selection action.
	// The file recommended for keeping must never be included by an automatic mark.
	function isDeletionCandidate(checkbox) {
		return checkbox.dataset.recommendedKeep !== 'true';
	}

	function folderKey(folder) {
		return (folder || '').replace(/[\\/]+$/, '').toLocaleLowerCase();
	}

	function updateSummary() {
		const ids = selectedIds();
		const totalBytes = ids.reduce((sum, id) => sum + Number(selection.selected[id] || 0), 0);
		const empty = ids.length === 0;

		if (selectionCount) {
			selectionCount.textContent = String(ids.length);
		}

		if (selectionSize) {
			selectionSize.textContent = formatBytes(totalBytes);
		}

		if (previewButton) {
			previewButton.disabled = empty || deletionBlocked;
		}

		if (clearSelectionButton) {
			clearSelectionButton.disabled = empty;
		}

		if (confirmDeleteButton) {
			confirmDeleteButton.disabled = empty || deletionBlocked;
		}

		if (previewPanel && !previewPanel.hidden && empty) {
			previewPanel.hidden = true;
		}
	}

	// Reconcile this page's checkboxes with the stored selection: honor explicit choices, and
	// seed the template's default candidate marks (th:checked) into the store the first time an
	// id is seen so the default still applies but only once.
	function applyStoredSelection() {
		checkboxes.forEach((checkbox) => {
			const id = checkbox.dataset.fileId;
			if (!id) {
				return;
			}

			if (Object.prototype.hasOwnProperty.call(selection.selected, id)) {
				checkbox.checked = true;
			} else if (selection.deselected.has(id)) {
				checkbox.checked = false;
			} else if (checkbox.checked) {
				selection.selected[id] = Number(checkbox.dataset.sizeBytes || 0);
			}
		});

		saveSelection();
	}

	function loadFingerprintFailures() {
		if (failuresLoaded) {
			return Promise.resolve();
		}

		if (failuresStatus) {
			failuresStatus.hidden = false;
			failuresStatus.textContent = t('js.folder.loading');
		}

		// Which list to load belongs to the tab, and the tab is the server's to know:
		// the dialog is shared markup and used to ask for photos on every tab.
		return fetch(failuresDialog.dataset.failuresUrl)
			.then((response) => {
				if (!response.ok) {
					throw new Error(`HTTP ${response.status}`);
				}
				return response.json();
			})
			.then((failures) => {
				if (failuresRows) {
					failuresRows.replaceChildren();
					failures.forEach((failure) => {
						const row = document.createElement('tr');
						const path = document.createElement('td');
						const reason = document.createElement('td');
						const error = document.createElement('td');

						const badge = document.createElement('span');

						// Colour, wording and hint all arrive decided: the browser never maps a
						// reason code to a sentence or to a palette.
						badge.className = `badge ${failure.tone || 'muted'}`;
						badge.textContent = failure.reasonLabel || '';
						badge.title = failure.reasonHint || '';

						path.textContent = failure.path || '—';
						reason.appendChild(badge);
						error.textContent = failure.error || t('js.duplicates.errorUnknown');
						row.append(path, reason, error);
						failuresRows.appendChild(row);
					});
				}

				failuresLoaded = true;
				if (failuresTableWrap) {
					failuresTableWrap.hidden = failures.length === 0;
				}
				if (failuresStatus) {
					failuresStatus.hidden = failures.length > 0;
					failuresStatus.textContent = t('js.duplicates.noFailures');
				}
			})
			.catch(() => {
				if (failuresStatus) {
					failuresStatus.hidden = false;
					failuresStatus.textContent = t('js.duplicates.loadFailuresError');
				}
			});
	}

	document.addEventListener('click', (event) => {
		if (event.target.closest('.fingerprint-failures-open')) {
			if (failuresDialog && !failuresDialog.open) {
				failuresDialog.showModal();
				loadFingerprintFailures();
			}
			return;
		}

		if (event.target.closest('.fingerprint-failures-close')) {
			failuresDialog?.close();
		}
	});

	checkboxes.forEach((checkbox) => checkbox.addEventListener('change', () => {
		syncCheckbox(checkbox);
		saveSelection();
		updateSummary();
	}));

	groupToggles.forEach((groupToggle) => {
		groupToggle.addEventListener('change', () => {
			const groupId = groupToggle.dataset.group;

			checkboxes
				.filter((checkbox) => checkbox.dataset.group === groupId
					&& isDeletionCandidate(checkbox))
				.forEach((checkbox) => {
					checkbox.checked = groupToggle.checked;
					syncCheckbox(checkbox);
				});

			saveSelection();
			updateSummary();
		});
	});

	function applyFolderMark(scope, selectedFolder, groupId) {
		const inFolder = (checkbox) => folderKey(checkbox.dataset.folder) === selectedFolder;
		const affectedGroupIds = new Set(checkboxes
			.filter((checkbox) => inFolder(checkbox) && (scope !== 'group' || checkbox.dataset.group === groupId))
			.map((checkbox) => checkbox.dataset.group));

		if (scope === 'replace') {
			// "Substituir toda a seleção" means exactly this folder's files, so mark every one of
			// them - including a group's recommended-keep when it lives here. The whole-group guard
			// below still prevents wiping an entire group (a survivor is always kept).
			checkboxes.forEach((checkbox) => {
				checkbox.checked = inFolder(checkbox);
			});
		} else if (scope === 'clear') {
			checkboxes.filter(inFolder).forEach((checkbox) => {
				checkbox.checked = false;
			});
		} else if (scope === 'group') {
			checkboxes
				.filter((checkbox) => inFolder(checkbox) && checkbox.dataset.group === groupId)
				.forEach((checkbox) => {
					checkbox.checked = isDeletionCandidate(checkbox);
				});
		} else {
			checkboxes.filter(inFolder).forEach((checkbox) => {
				checkbox.checked = isDeletionCandidate(checkbox);
			});
		}

		// A group's recommended-keep can now be checked - by this folder action (replace) or by an
		// earlier manual click. If every copy of a group ends up checked, restore the same protected
		// file used by "Sugerir seleção" so no folder action can ever wipe a whole group.
		affectedGroupIds.forEach((affectedGroupId) => {
			const groupCheckboxes = checkboxes.filter((checkbox) => checkbox.dataset.group === affectedGroupId);
			const recommendedKeep = groupCheckboxes.find((checkbox) => !isDeletionCandidate(checkbox));

			if (recommendedKeep && groupCheckboxes.every((checkbox) => checkbox.checked)) {
				recommendedKeep.checked = false;
			}
		});

		checkboxes.forEach(syncCheckbox);
		saveSelection();
		updateSummary();
	}

	folderButtons.forEach((folderButton) => {
		folderButton.addEventListener('click', () => {
			const selectedFolder = folderKey(folderButton.dataset.folder);
			if (!selectedFolder || !folderMarkDialog) {
				return;
			}

			folderMarkDialog.dataset.folder = selectedFolder;
			folderMarkDialog.dataset.group = folderButton.dataset.group || '';
			// Keep the ORIGINAL path (case preserved) for the exclude action: the DB match is
			// case-sensitive, unlike the lowercased key used for the client-side marks.
			folderMarkDialog.dataset.folderPath = folderButton.dataset.folder || '';

			if (folderMarkPath) {
				folderMarkPath.textContent = folderButton.dataset.folder || '—';
			}

			folderMarkDialog.showModal();
		});
	});

	if (folderMarkDialog) {
		folderMarkDialog.querySelectorAll('.js-folder-mark-apply').forEach((option) => {
			option.addEventListener('click', () => {
				applyFolderMark(option.dataset.scope, folderMarkDialog.dataset.folder,
					folderMarkDialog.dataset.group);
				folderMarkDialog.close();
			});
		});

		folderMarkDialog.querySelector('.js-folder-mark-close')
			?.addEventListener('click', () => folderMarkDialog.close());

		folderMarkDialog.querySelector('.js-folder-exclude')?.addEventListener('click', () => {
			const folderPath = folderMarkDialog.dataset.folderPath;
			folderMarkDialog.close();

			if (folderPath) {
				excludeFromComparison('/app/duplicates/exclude/folder', { folder: folderPath });
			}
		});
	}

	// Hides a file/folder from BOTH duplicate tabs without deleting anything (the exclusion is
	// reversible from Configurações). The exact tab re-filters on reload; the similar tab's cache
	// was cleared server-side, so the reload recomputes without the excluded items.
	function excludeFromComparison(url, body, trigger) {
		const csrf = document.querySelector("input[name='_csrf']");

		if (trigger) {
			trigger.disabled = true;
		}

		fetch(url, {
			method: 'POST',
			headers: {
				'Content-Type': 'application/json',
				'X-CSRF-TOKEN': csrf ? csrf.value : ''
			},
			body: JSON.stringify(body)
		})
			.then((response) => {
				if (!response.ok) {
					throw new Error(`HTTP ${response.status}`);
				}

				window.location.reload();
			})
			.catch(() => {
				if (trigger) {
					trigger.disabled = false;
				}
			});
	}

	document.addEventListener('click', (event) => {
		const excludeFileButton = event.target.closest('.js-exclude-file');

		if (excludeFileButton && excludeFileButton.dataset.fileId) {
			excludeFromComparison('/app/duplicates/exclude/file',
				{ publicId: excludeFileButton.dataset.fileId }, excludeFileButton);
		}
	});

	if (suggestSelectionButton) {
		// Re-apply the automatic suggestion on this page: mark the candidates (everything that
		// is not the recommended "Manter") and clear any earlier deselection for them.
		suggestSelectionButton.addEventListener('click', () => {
			checkboxes.forEach((checkbox) => {
				checkbox.checked = isDeletionCandidate(checkbox);
				syncCheckbox(checkbox);
			});

			saveSelection();
			updateSummary();
		});
	}

	if (clearSelectionButton) {
		// Clear the complete persisted selection for this tab, not just the current DOM page.
		// Every id selected on another visited page is kept in "deselected" so the template's
		// default checked state does not silently select it again when that page is revisited.
		clearSelectionButton.addEventListener('click', () => {
			Object.keys(selection.selected).forEach((id) => selection.deselected.add(id));

			checkboxes.forEach((checkbox) => {
				const id = checkbox.dataset.fileId;

				checkbox.checked = false;
				delete selection.selected[id];

				if (id) {
					selection.deselected.add(id);
				}
			});

			groupToggles.forEach((groupToggle) => {
				groupToggle.checked = false;
			});

			saveSelection();
			updateSummary();

			if (previewPanel) {
				previewPanel.hidden = true;
			}

			if (previewList) {
				previewList.replaceChildren();
			}

			setDeleteStatus('', false);
		});
	}

	if (previewButton) {
		previewButton.addEventListener('click', () => {
			// The preview lists the selected files on THIS page (their paths are only in the
			// current DOM); the summary/count above already reflect every page.
			if (previewList) {
				previewList.innerHTML = '';

				selectedCheckboxes().forEach((checkbox) => {
					const item = document.createElement('li');

					item.textContent = `${checkbox.dataset.path} (${formatBytes(Number(checkbox.dataset.sizeBytes || 0))})`;
					previewList.appendChild(item);
				});
			}

			if (previewPanel) {
				previewPanel.hidden = false;
				previewPanel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
			}
		});
	}

	function setDeleteStatus(message, isError) {
		if (!deleteStatus) {
			return;
		}

		deleteStatus.hidden = !message;
		deleteStatus.textContent = message || '';
		deleteStatus.classList.toggle('error-text', Boolean(isError));
	}

	function forgetDeleted(ids) {
		ids.forEach((id) => {
			delete selection.selected[id];
			selection.deselected.delete(id);
		});

		saveSelection();
	}

	function removeDeletedRows(ids) {
		const idSet = new Set(ids);

		checkboxes
			.filter((checkbox) => idSet.has(checkbox.dataset.fileId))
			.forEach((checkbox) => {
				const container = checkbox.closest('tr') || checkbox.closest('.explorer-tile');

				if (container) {
					container.remove();
				}
			});

		// Keep each group card consistent with what's left in it: refresh the "N ..." count in the
		// header, and drop the whole card when fewer than two files remain (a group needs at least
		// two to exist). Otherwise the header would keep showing the old count over a single photo.
		document.querySelectorAll('.duplicate-group').forEach((group) => {
			const remaining = group.querySelectorAll('.js-delete-checkbox').length;

			if (remaining < 2) {
				group.remove();
				return;
			}

			const header = group.querySelector('.duplicate-group-header strong');

			if (header) {
				header.textContent = header.textContent.replace(/^\d+/, String(remaining));
			}
		});
	}

	function setDeleteProgress(percent) {
		if (!deleteProgressBar || !deleteProgressFill) {
			return;
		}

		if (percent === null) {
			deleteProgressBar.hidden = true;
			return;
		}

		const clamped = Math.max(0, Math.min(100, percent));
		deleteProgressBar.hidden = false;
		deleteProgressBar.setAttribute('aria-valuenow', String(clamped));
		deleteProgressFill.style.width = `${clamped}%`;
	}

	// The deletion runs in the background (sequential secure moves, ~5s for dozens of files), so the
	// screen kicks it off and then polls for a "Movendo X de N" bar instead of blocking on the POST.
	function runDelete(ids) {
		const csrf = document.querySelector("input[name='_csrf']");

		confirmDeleteButton.disabled = true;
		setDeleteStatus(t('js.duplicates.moving'), false);
		setDeleteProgress(0);

		fetch('/app/duplicates/delete', {
			method: 'POST',
			headers: {
				'Content-Type': 'application/json',
				'X-CSRF-TOKEN': csrf ? csrf.value : ''
			},
			body: JSON.stringify({ ids })
		})
			.then((response) => {
				if (!response.ok) {
					throw new Error(`HTTP ${response.status}`);
				}
				return response.json();
			})
			.then((progress) => handleDeleteProgress(progress, ids))
			.catch(() => {
				setDeleteStatus(t('js.duplicates.deleteError'), true);
				setDeleteProgress(null);
				confirmDeleteButton.disabled = false;
			});
	}

	function handleDeleteProgress(progress, ids) {
		if (progress && progress.running) {
			const total = progress.total || ids.length;
			setDeleteStatus(t('js.duplicates.movingProgress', progress.processed, total), false);
			setDeleteProgress(progress.percent || 0);
			window.setTimeout(() => pollDeleteProgress(ids), 700);
			return;
		}

		finalizeDelete(progress ? progress.result : null, ids);
	}

	function pollDeleteProgress(ids) {
		fetch('/app/duplicates/delete/progress')
			.then((response) => {
				if (!response.ok) {
					throw new Error(`HTTP ${response.status}`);
				}
				return response.json();
			})
			.then((progress) => handleDeleteProgress(progress, ids))
			.catch(() => {
				setDeleteStatus(t('js.duplicates.progressError'), true);
				setDeleteProgress(null);
				confirmDeleteButton.disabled = false;
			});
	}

	function finalizeDelete(result, ids) {
		confirmDeleteButton.disabled = false;
		setDeleteProgress(null);

		if (!result) {
			setDeleteStatus(t('js.duplicates.completedReload'), false);
			return;
		}

		if (!result.configured) {
			setDeleteStatus(result.message || t('js.duplicates.configureQuarantine'), true);
			return;
		}

		if (result.errors === 0) {
			// Instant feedback: drop the moved ids from the persisted selection and remove their
			// rows in place, then reload so the server re-paginates - the page and group totals
			// refresh and the following groups fill the gaps left behind (otherwise the counters
			// and "Página X de N" stay frozen at the load-time values). The similar-tab cache is
			// pruned server-side, so this reload never recomputes similarity.
			forgetDeleted(ids);
			removeDeletedRows(ids);
			updateSummary();
			setDeleteStatus(t('js.duplicates.moved', result.moved), false);
			window.setTimeout(() => window.location.reload(), 1200);
			return;
		}

		setDeleteStatus(t('js.duplicates.movedWithErrors', result.moved, result.errors), true);
	}

	if (confirmDeleteButton && deleteConfirmDialog) {
		confirmDeleteButton.addEventListener('click', () => {
			const ids = selectedIds();

			if (ids.length === 0) {
				return;
			}

			if (deleteConfirmCount) {
				deleteConfirmCount.textContent = String(ids.length);
			}

			deleteConfirmDialog.showModal();
		});

		deleteConfirmDialog.querySelector('.js-delete-cancel')
			?.addEventListener('click', () => deleteConfirmDialog.close());

		deleteConfirmDialog.querySelector('.js-delete-confirm')?.addEventListener('click', () => {
			const ids = selectedIds();

			deleteConfirmDialog.close();

			if (ids.length > 0) {
				runDelete(ids);
			}
		});
	}

	/**
	 * A cached thumbnail can fail to load - most commonly because the file was deleted from
	 * disk after being cataloged and hasn't been reconciled yet, or because the path falls
	 * under a configured scan-exclusion pattern. The browser's native broken-image glyph is
	 * confusing, so on error the element is swapped for the same file-type icon used when there
	 * was never a thumbnail attempt.
	 */
	function bindThumbFallback() {
		document.querySelectorAll('.js-thumb').forEach((element) => {
			element.addEventListener('error', () => {
				const icon = document.createElement('i');

				icon.className = `file-symbol bi ${element.dataset.iconClass || 'bi-file-earmark-fill generic'}`;
				icon.title = element.dataset.iconLabel || t('js.duplicates.file');

				if (element.classList.contains('details-thumb')) {
					icon.classList.add('details-thumb');
				}

				element.replaceWith(icon);
			});
		});
	}

	// Whether the published analysis still describes the library is the expensive
	// question on this screen - it identifies the whole eligible library to compare
	// one digest - so the server no longer answers it before sending the page. It is
	// asked once here, after the results are already on screen, and the backend
	// stays the only one deciding: this reads a verdict, it does not compute one.
	function checkFreshness() {
		const banner = document.getElementById('similarityFreshness');

		if (!banner || !banner.dataset.freshnessUrl) {
			return;
		}

		const show = (state) => {
			banner.querySelectorAll('[data-freshness-state]').forEach((element) => {
				element.hidden = element.dataset.freshnessState !== state;
			});
		};

		fetch(banner.dataset.freshnessUrl, { headers: { Accept: 'application/json' } })
			.then((response) => {
				if (!response.ok) {
					throw new Error(`HTTP ${response.status}`);
				}
				return response.json();
			})
			.then((freshness) => {
				if (freshness.outdated) {
					show('outdated');
					return;
				}

				// Current, or never published: either way there is nothing to warn about,
				// and the banner leaves rather than saying so.
				banner.hidden = true;
			})
			// A failed check never becomes "current": the screen says it could not tell,
			// and the results already rendered stay usable.
			.catch(() => show('error'));
	}

	// The panels that used to keep this screen alive by re-fetching all of it. They
	// now read the activity poll the app shell already runs: one 0,9 kB request on a
	// backoff, shared, instead of a full render of this page every few seconds -
	// which on a large library is seconds of server work per cycle, and stacked with
	// whatever else was running. The backend still decides what is running; this only
	// shows or hides markup the server already sent.
	function bindActivityPanels() {
		const panels = Array.from(document.querySelectorAll('[data-activity-watch]'));

		if (!panels.length) {
			return;
		}

		// Every active execution, not only the one the banner draws: the snapshot names
		// a primary and lists the rest, and an inventory can be the one it is not
		// drawing.
		const activeTypes = (snapshot) => {
			const activities = [];

			if (snapshot && snapshot.primary) {
				activities.push(snapshot.primary);
			}

			if (snapshot && snapshot.others) {
				activities.push(...snapshot.others);
			}

			return activities.map((activity) => activity.executionType);
		};

		document.addEventListener('nimbus-file-manager:activity', (event) => {
			const running = activeTypes(event.detail);

			panels.forEach((panel) => {
				if (running.includes(panel.dataset.activityWatch)) {
					return;
				}

				if (panel.dataset.activityOnIdle === 'hide') {
					panel.hidden = true;
					return;
				}

				// The analysis produced groups, and those are rendered by the server. The
				// screen says so and offers the way there; it does not navigate on its own.
				const announcement = panel.querySelector('[data-activity-idle-announcement]');

				if (announcement) {
					announcement.hidden = false;
				}
			});
		});
	}

	// The fingerprint panel asks for its own numbers. They are catalog state - true
	// with nothing running - so they are not in the activity snapshot, and asking for
	// them used to mean re-fetching this entire page every four seconds. The rules of
	// the shared poll apply here too: never two requests at once, slower while nobody
	// is looking, and a failed answer changes nothing on screen.
	function bindBacklogPoll() {
		const panel = document.getElementById('fingerprintProgressRegion');

		if (!panel || !panel.dataset.backlogUrl) {
			return;
		}

		const ACTIVE_MILLIS = 4000;
		const HIDDEN_MILLIS = 30000;
		const ERROR_MILLIS = 15000;

		let polling = false;
		let timer = null;

		const set = (selector, value) => {
			const element = panel.querySelector(selector);

			if (element) {
				element.textContent = value;
			}
		};

		const schedule = (wait) => {
			window.clearTimeout(timer);
			timer = window.setTimeout(poll, document.hidden ? HIDDEN_MILLIS : wait);
		};

		function render(backlog) {
			const percent = `${backlog.percent.toFixed(2)}%`;

			set('[data-backlog-percent]', percent);
			set('[data-backlog-eta]', backlog.etaLabel || '');
			set('[data-backlog-done]', backlog.done);
			set('[data-backlog-total]', backlog.total);
			set('[data-backlog-pending]', backlog.pending);
			set('[data-backlog-failed]', backlog.failed);

			// The other medium's fingerprint: shown while it is the one running, hidden
			// otherwise. Which of the two runs first is the queue's business - this only
			// draws the sentence the server sent, or nothing.
			const other = panel.querySelector('[data-backlog-other]');

			if (other) {
				other.hidden = !backlog.other;

				if (backlog.other) {
					set('[data-backlog-other-label]', backlog.other.label);
					set('[data-backlog-other-percent]', `${backlog.other.percent.toFixed(2)}%`);
				}
			}

			const bar = panel.querySelector('[data-backlog-bar]');
			const fill = panel.querySelector('[data-backlog-fill]');

			if (bar) {
				bar.setAttribute('aria-valuenow', backlog.percent);
			}

			if (fill) {
				fill.style.width = `${backlog.percent}%`;
			}

			// Nothing left to fingerprint: the panel has said all it had to say. What
			// appears next needs the page, and the user asks for that.
			if (backlog.pending === 0) {
				panel.hidden = true;

				return null;
			}

			return ACTIVE_MILLIS;
		}

		function poll() {
			if (polling) {
				return;
			}

			polling = true;

			fetch(panel.dataset.backlogUrl, { headers: { Accept: 'application/json' } })
				.then((response) => {
					if (!response.ok) {
						throw new Error(`HTTP ${response.status}`);
					}

					return response.json();
				})
				.then((backlog) => {
					const next = render(backlog);

					if (next) {
						schedule(next);
					}
				})
				// A failed poll says nothing about the backlog: the numbers on screen stay
				// exactly as they were rather than reading as finished.
				.catch(() => schedule(ERROR_MILLIS))
				.finally(() => {
					polling = false;
				});
		}

		schedule(ACTIVE_MILLIS);
	}

	bindThumbFallback();
	applyStoredSelection();
	updateSummary();
	checkFreshness();
	bindActivityPanels();
	bindBacklogPoll();
});