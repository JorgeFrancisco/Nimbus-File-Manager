(function () {
	"use strict";

	// Generic folder picker shared by every page that lets the user choose a
	// local folder (onboarding, organization, settings). Each ".folder-picker-open"
	// button points at its target <input> through data-target (a CSS selector);
	// as a fallback it uses input[name="value"] inside the button's own form (the
	// settings-row layout). One shared <dialog id="folderPicker"> per page, brought
	// in via fragments/folder-picker.html. Backend: GET /app/settings/folders.

	var dialog, currentPath = null, parentPath = null, activeInput = null;
	var t = window.NimbusFileManagerI18n.t;

	function el(id) {
		return document.getElementById(id);
	}

	function load(path) {
		var list = el("folderPickerList"), error = el("folderPickerError");
		var params = new URLSearchParams();
		if (path) params.set("path", path);
		list.textContent = t("js.folder.loading");
		error.hidden = true;
		fetch("/app/settings/folders?" + params.toString()).then(function (response) {
			if (!response.ok) throw new Error();
			return response.json();
		}).then(function (data) {
			currentPath = data.currentPath;
			parentPath = data.parentPath;
			el("folderPickerPath").textContent = currentPath || t("js.folder.thisComputer");
			el("folderPickerChoose").disabled = !currentPath;
			el("folderPickerUp").disabled = !currentPath;
			list.replaceChildren();
			data.directories.forEach(function (directory) {
				var button = document.createElement("button");
				button.type = "button";
				button.className = "folder-picker-entry";
				button.innerHTML = '<i class="bi bi-folder2" aria-hidden="true"></i><span></span>';
				button.querySelector("span").textContent = directory.name;
				button.addEventListener("click", function () { load(directory.path); });
				list.appendChild(button);
			});
			if (!data.directories.length) list.textContent = t("js.folder.empty");
			error.textContent = data.truncated ? t("js.folder.truncated") : "";
			error.hidden = !data.truncated;
		}).catch(function () {
			currentPath = null;
			parentPath = null;
			list.replaceChildren();
			el("folderPickerChoose").disabled = true;
			el("folderPickerUp").disabled = false;
			error.textContent = t("js.folder.openError");
			error.hidden = false;
		});
	}

	function resolveInput(trigger) {
		var target = trigger.getAttribute("data-target");
		if (target) return document.querySelector(target);
		var form = trigger.closest("form");
		return form ? form.querySelector('input[name="value"]') : null;
	}

	document.addEventListener("DOMContentLoaded", function () {
		dialog = el("folderPicker");
		var triggers = document.querySelectorAll(".folder-picker-open");
		if (!dialog || !triggers.length) return;

		triggers.forEach(function (trigger) {
			trigger.addEventListener("click", function () {
				activeInput = resolveInput(trigger);
				if (!activeInput) return;
				dialog.showModal();
				load(activeInput.value);
			});
		});

		var closeButton = dialog.querySelector(".folder-picker-close");
		if (closeButton) closeButton.addEventListener("click", function () { dialog.close(); });

		el("folderPickerUp").addEventListener("click", function () { load(parentPath); });
		el("folderPickerChoose").addEventListener("click", function () {
			if (currentPath && activeInput) {
				activeInput.value = currentPath;
				dialog.close();
				activeInput.focus();
				// Let any page-specific listeners (e.g. the settings library-switch
				// confirm) know the value changed via the picker, not just typing.
				activeInput.dispatchEvent(new Event("change", { bubbles: true }));
			}
		});
	});
})();