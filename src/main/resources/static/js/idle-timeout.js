(function () {
	// How long the warning modal stays up (with a live countdown) before auto-logout fires. This
	// window is carved out of the end of the configured idle timeout, not added on top of it - eg.
	// with a 5 minute timeout, the modal appears at the 4 minute mark and the countdown ends the
	// session at exactly 5 minutes of inactivity, matching what the settings screen promises.
	var WARNING_LEAD_SECONDS = 60;
	var ACTIVITY_EVENTS = ["click", "mousemove", "keydown", "scroll", "touchstart"];
	var DEFAULT_TIMEOUT_MINUTES = 5;

	var idleTimer = null;
	var countdownTimer = null;
	var warningShown = false;

	function shell() {
		return document.querySelector(".shell");
	}

	function timeoutMinutes() {
		var element = shell();
		var value = element ? Number(element.getAttribute("data-idle-timeout-minutes")) : 0;

		return value > 0 ? value : DEFAULT_TIMEOUT_MINUTES;
	}

	function overlay() {
		return document.getElementById("idleModalOverlay");
	}

	function showModal() {
		var element = overlay();

		if (element) {
			element.hidden = false;
		}
	}

	function hideModal() {
		var element = overlay();

		if (element) {
			element.hidden = true;
		}
	}

	function updateCountdown(secondsLeft) {
		var element = document.getElementById("idleModalCountdown");

		if (element) {
			element.textContent = secondsLeft;
		}
	}

	function submitLogout(reason) {
		var form = document.getElementById("logoutForm");
		var reasonField = document.getElementById("logoutReasonField");

		if (!form) {
			// Defensive path (the layout normally always renders #logoutForm): POST /logout
			// rather than GET it. A GET is never processed by the CSRF-protected logout filter;
			// a POST is - and even without a token the resilient access-denied handler turns it
			// into a real logout + redirect.
			var fallback = document.createElement("form");
			fallback.method = "post";
			fallback.action = "/logout";
			document.body.appendChild(fallback);
			fallback.submit();

			return;
		}

		if (reasonField) {
			reasonField.value = reason || "";
		}

		form.submit();
	}

	function clearTimers() {
		if (idleTimer) {
			window.clearTimeout(idleTimer);
			idleTimer = null;
		}

		if (countdownTimer) {
			window.clearInterval(countdownTimer);
			countdownTimer = null;
		}
	}

	function startCountdown() {
		var secondsLeft = WARNING_LEAD_SECONDS;

		updateCountdown(secondsLeft);

		countdownTimer = window.setInterval(function () {
			secondsLeft -= 1;

			updateCountdown(secondsLeft);

			if (secondsLeft <= 0) {
				window.clearInterval(countdownTimer);
				submitLogout("inactivity");
			}
		}, 1000);
	}

	function onIdleWarning() {
		warningShown = true;

		showModal();
		startCountdown();
	}

	function scheduleIdleWarning() {
		clearTimers();

		var totalMs = timeoutMinutes() * 60 * 1000;
		var warningLeadMs = Math.min(WARNING_LEAD_SECONDS * 1000, totalMs);
		var delayMs = Math.max(totalMs - warningLeadMs, 0);

		idleTimer = window.setTimeout(onIdleWarning, delayMs);
	}

	function resetOnActivity() {
		// Once the warning is up, plain page activity (eg. the mouse drifting) no longer resets
		// it silently - the user has to make an explicit choice (Continuar/Sair), same as the
		// requirement asked for.
		if (warningShown) {
			return;
		}

		scheduleIdleWarning();
	}

	function bindActivity() {
		ACTIVITY_EVENTS.forEach(function (eventName) {
			document.addEventListener(eventName, resetOnActivity, { passive: true });
		});

		document.addEventListener("visibilitychange", function () {
			if (!document.hidden) {
				resetOnActivity();
			}
		});
	}

	function bindModalButtons() {
		var continueButton = document.getElementById("idleModalContinue");
		var logoutButton = document.getElementById("idleModalLogout");

		if (continueButton) {
			continueButton.addEventListener("click", function () {
				warningShown = false;

				hideModal();

				// Any authenticated request already resets the server-side session's last-accessed
				// clock, so this call just needs to happen - the response itself is not used.
				fetch("/app/session/keep-alive").catch(function () {});

				scheduleIdleWarning();
			});
		}

		if (logoutButton) {
			logoutButton.addEventListener("click", function () {
				submitLogout("");
			});
		}
	}

	document.addEventListener("DOMContentLoaded", function () {
		if (!shell()) {
			return;
		}

		bindActivity();
		bindModalButtons();
		scheduleIdleWarning();
	});
})();