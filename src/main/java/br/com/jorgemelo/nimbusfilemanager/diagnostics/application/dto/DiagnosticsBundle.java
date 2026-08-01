package br.com.jorgemelo.nimbusfilemanager.diagnostics.application.dto;

import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import br.com.jorgemelo.nimbusfilemanager.diagnostics.application.DiagnosticsBundleService;

/**
 * Result of {@link DiagnosticsBundleService#export}: the suggested download
 * file name and content type for the response headers, paired with the
 * streaming body that writes the archive. A plain holder, so the service never
 * touches {@code ResponseEntity}/{@code HttpHeaders} - those stay in the
 * controller.
 */
public record DiagnosticsBundle(String fileName, String contentType, StreamingResponseBody body) {
}