(function () {
	"use strict";

	var catalog = window.NimbusFileManagerMessageCatalog || {};

	window.NimbusFileManagerI18n = {
		locale: document.documentElement.lang || "pt-BR",
		t: function (key) {
			var args = Array.prototype.slice.call(arguments, 1);
			var message = Object.prototype.hasOwnProperty.call(catalog, key) ? catalog[key] : key;

			return String(message).replace(/\{(\d+)\}/g, function (match, index) {
				return Number(index) < args.length ? String(args[Number(index)]) : match;
			});
		}
	};
})();