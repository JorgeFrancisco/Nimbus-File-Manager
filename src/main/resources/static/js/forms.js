(function () {
	var t = window.NimbusFileManagerI18n.t;

	/**
	 * Delegated from the document instead of bound element by element: a panel
	 * that refreshes itself replaces its own markup, and listeners attached at
	 * load go away with the nodes they were attached to. That is how deleting a
	 * backup stopped asking for confirmation - the form on screen was not the
	 * form that had been bound.
	 */
	function bindConfirmations() {
		document.addEventListener("click", function (event) {
			var element = event.target.closest ? event.target.closest("[data-confirm]") : null;

			if (element && !window.confirm(element.getAttribute("data-confirm"))) {
				event.preventDefault();
				event.stopPropagation();
			}
		});
	}

	function bindLoadingState() {
		document.addEventListener("submit", function (event) {
			event.target.querySelectorAll("button[type='submit']").forEach(function (button) {
				button.dataset.originalText = button.textContent;
				button.textContent = t("js.processing");
				button.disabled = true;
			});
		});
	}

	/**
	 * Attaches a <datalist> of recently-used values (stored in localStorage, client-side only - no
	 * backend involved) to any input marked data-recent-values="someKey", so folder-path fields
	 * (onboarding, organization, files address bar) suggest what was typed before instead of
	 * requiring the exact path to be retyped every time.
	 */
	function bindRecentValues() {
		document.querySelectorAll("[data-recent-values]").forEach(function (input) {
			var key = "nimbusFileManager.recentValues." + input.getAttribute("data-recent-values");
			var datalist = document.createElement("datalist");
			var datalistId = "recent-values-" + input.getAttribute("data-recent-values");

			datalist.id = datalistId;
			input.setAttribute("list", datalistId);
			input.insertAdjacentElement("afterend", datalist);

			function stored() {
				try {
					var values = JSON.parse(window.localStorage.getItem(key) || "[]");

					return Array.isArray(values) ? values : [];
				} catch (e) {
					return [];
				}
			}

			function render() {
				datalist.innerHTML = "";
				stored().forEach(function (value) {
					var option = document.createElement("option");

					option.value = value;
					datalist.appendChild(option);
				});
			}

			function remember(value) {
				if (!value) {
					return;
				}

				var next = [value].concat(stored().filter(function (existing) { return existing !== value; }));

				window.localStorage.setItem(key, JSON.stringify(next.slice(0, 6)));
			}

			render();

			var form = input.closest("form");

			if (form) {
				form.addEventListener("submit", function () {
					remember(input.value.trim());
				});
			}
		});
	}

	/**
	 * Fills the target input with a strong, randomly generated password (Web Crypto, not
	 * Math.random) and reveals it as plain text, for forms like "Novo usuario" that would otherwise
	 * require the admin to invent and remember a temporary password by hand.
	 */
	function bindPasswordGenerator() {
		document.querySelectorAll("[data-generate-password]").forEach(function (button) {
			button.addEventListener("click", function () {
				var input = document.getElementById(button.getAttribute("data-generate-password"));

				if (!input || !window.crypto || !window.crypto.getRandomValues) {
					return;
				}

				var charset = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%";
				var randomValues = new Uint32Array(14);

				window.crypto.getRandomValues(randomValues);

				input.value = Array.prototype.map.call(randomValues, function (value) {
					return charset[value % charset.length];
				}).join("");
				input.type = "text";
				input.focus();
				input.select();
			});
		});
	}

	/**
	 * Pairs a <select> whose <option>s carry a data-description (set server-side from backend
	 * enum/config, never hardcoded in the template or here) with a hint element referenced via
	 * data-option-hint="someId", and keeps that hint showing the currently selected option's
	 * description. Generic on purpose so any future backend-driven select can reuse it.
	 */
	function bindOptionHints() {
		document.querySelectorAll("[data-option-hint]").forEach(function (select) {
			var hint = document.getElementById(select.getAttribute("data-option-hint"));

			if (!hint) {
				return;
			}

			function update() {
				var option = select.options[select.selectedIndex];

				if (option && option.dataset.description) {
					hint.textContent = option.dataset.description;
				}
			}

			select.addEventListener("change", update);
			update();
		});
	}

	function bindTableFilter() {
		document.querySelectorAll("[data-table-filter]").forEach(function (input) {
			var selector = input.getAttribute("data-table-filter");
			var table = document.querySelector(selector);

			if (!table) {
				return;
			}

			input.addEventListener("input", function () {
				var query = input.value.trim().toLowerCase();

				table.querySelectorAll("tbody tr").forEach(function (row) {
					row.hidden = query.length > 0 && !row.textContent.toLowerCase().includes(query);
				});
			});
		});
	}

	document.addEventListener("DOMContentLoaded", function () {
		bindConfirmations();
		bindLoadingState();
		bindTableFilter();
		bindRecentValues();
		bindPasswordGenerator();
		bindOptionHints();
	});
})();