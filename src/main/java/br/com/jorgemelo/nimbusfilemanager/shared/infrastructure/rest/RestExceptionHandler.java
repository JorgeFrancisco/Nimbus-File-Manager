package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.rest;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

@RestControllerAdvice(basePackages = { "br.com.jorgemelo.nimbusfilemanager.catalog.infrastructure.rest",
		"br.com.jorgemelo.nimbusfilemanager.diagnostics.infrastructure.rest",
		"br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.rest",
		"br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.rest",
		"br.com.jorgemelo.nimbusfilemanager.map.infrastructure.rest",
		"br.com.jorgemelo.nimbusfilemanager.media.infrastructure.rest",
		"br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.rest",
		"br.com.jorgemelo.nimbusfilemanager.organization.infrastructure.rest",
		"br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.rest",
		"br.com.jorgemelo.nimbusfilemanager.statistics.infrastructure.rest",
		"br.com.jorgemelo.nimbusfilemanager.thumbnail.infrastructure.rest",
		"br.com.jorgemelo.nimbusfilemanager.timeline.infrastructure.rest" })
public class RestExceptionHandler extends LocalizedComponent {

	private static final Logger LOGGER = LoggerFactory.getLogger(RestExceptionHandler.class);

	private static final String ERROR = "error";

	@ExceptionHandler(IllegalArgumentException.class)
	ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
		return ResponseEntity.badRequest().body(Map.of(ERROR, e.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
		return ResponseEntity.badRequest()
				.body(Map.of(ERROR, message("backend.api.invalidRequest"), "details", e.getMessage()));
	}

	/**
	 * A query/path parameter the caller sent in the wrong shape is the caller's
	 * mistake, not a server failure: it answers 400, and the log line is a single
	 * WARN naming the parameter. It used to fall through to the generic handler,
	 * which meant a 500 plus a full stack trace at ERROR - a template that built
	 * {@code ?w=null} for every thumbnail on screen once filled the log with
	 * hundreds of identical stacks, drowning the failures that do need
	 * investigating.
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<Map<String, Object>> typeMismatch(MethodArgumentTypeMismatchException e) {
		Class<?> requiredType = e.getRequiredType();

		LOGGER.warn("Rejected {}={} on /api/**: not a valid {}", e.getName(), e.getValue(),
				requiredType == null ? "value" : requiredType.getSimpleName());

		return ResponseEntity.badRequest().body(Map.of(ERROR, message("backend.api.invalidRequest")));
	}

	/**
	 * The browser is free to cancel an image/video response when an element leaves
	 * the viewport, a filter changes or the page is refreshed. At that point the
	 * response may already be committed with an image or video content type, so
	 * attempting to serialize the generic JSON error body would only produce a
	 * second HttpMessageNotWritableException. There is nothing actionable for the
	 * client.
	 */
	@ExceptionHandler(AsyncRequestNotUsableException.class)
	void clientDisconnected(AsyncRequestNotUsableException e) {
		LOGGER.debug("Client disconnected while response content was being streamed: {}", e.getMessage());
	}

	/**
	 * Every other exception (NPE, {@code IOException} from a file operation,
	 * JPA/JDBC failures, ...) lands here. {@code /api/**} is public
	 * ({@code SecurityConfig}), so {@code e.getMessage()} must never go straight to
	 * the response - a raw JDBC or filesystem exception message routinely contains
	 * internal file paths, table/column names, or connection details. The full
	 * exception (with stack trace) is logged server-side with a short
	 * reference id; the client only gets that id plus a generic message, so an
	 * admin can still correlate a support report back to the real cause via the
	 * logs without the caller ever seeing internal details.
	 */
	@ExceptionHandler(Exception.class)
	ResponseEntity<Map<String, Object>> generic(Exception e) {
		String reference = UUID.randomUUID().toString().substring(0, 8);

		LOGGER.error("Unhandled exception on /api/** [ref={}]", reference, e);

		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(Map.of(ERROR, message("backend.api.internalError"), "reference", reference));
	}
}