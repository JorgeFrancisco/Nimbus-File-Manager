package br.com.jorgemelo.nimbusfilemanager.backup.infrastructure.web;

import java.io.IOException;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import br.com.jorgemelo.nimbusfilemanager.backup.application.CatalogBackupAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.shared.application.BackgroundWorkGate;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Holds the screens off while a restore is replacing the catalog.
 *
 * <p>
 * {@code pg_restore --clean} drops each object before recreating it, so for a
 * few minutes any table can be momentarily absent. Anything rendering in that
 * window fails on a table that is about to exist again - which is exactly what
 * happened the first time a restore ran with a browser open on the page, and it
 * reads as a broken application rather than as work in progress.
 *
 * <p>
 * The reply is written here instead of rendered as a view: a view goes through
 * the layout and its advices, and those read the database this is protecting
 * the caller from. Only the bundles are touched, never a table.
 */
@Component
public class RestoreInProgressInterceptor extends LocalizedComponent implements HandlerInterceptor {

	/** Long enough not to hammer the server, short enough to feel alive. */
	private static final int RETRY_SECONDS = 3;

	private static final String TITLE = "backend.backup.restoreInProgress";

	private final CatalogBackupAsyncRunner backupRunner;
	private final BackgroundWorkGate backgroundWorkGate;

	public RestoreInProgressInterceptor(CatalogBackupAsyncRunner backupRunner, BackgroundWorkGate backgroundWorkGate) {
		this.backupRunner = backupRunner;
		this.backgroundWorkGate = backgroundWorkGate;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
			throws IOException {
		// Only a restore: a backup reads the database and leaves it whole.
		if (!backgroundWorkGate.restoring()) {
			return true;
		}

		response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
		response.setHeader("Retry-After", Integer.toString(RETRY_SECONDS));

		if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
			response.setContentType("text/plain;charset=UTF-8");
			response.getWriter().write(message(TITLE));

			return false;
		}

		response.setContentType("text/html;charset=UTF-8");
		response.getWriter().write(page());

		return false;
	}

	/**
	 * Deliberately plain: no stylesheet, no script, nothing that could fail while
	 * the database is mid-restore. The meta refresh is what brings the person back
	 * to the real screen once it ends.
	 */
	private String page() {
		return """
				<!DOCTYPE html>
				<html lang="%s"><head><meta charset="utf-8">
				<meta http-equiv="refresh" content="%d">
				<title>%s</title></head>
				<body style="font-family:system-ui,sans-serif;margin:4rem auto;max-width:34rem;line-height:1.6">
				<h1 style="font-size:1.25rem">%s</h1>
				<p>%s</p>
				<p><progress></progress></p>
				</body></html>
				""".formatted(LocaleContextHolder.getLocale().toLanguageTag(), RETRY_SECONDS,
				message(TITLE), message(TITLE),
				message("backend.backup.restoreWait", megabytes()));
	}

	private long megabytes() {
		return backupRunner.progress().megabytes();
	}
}