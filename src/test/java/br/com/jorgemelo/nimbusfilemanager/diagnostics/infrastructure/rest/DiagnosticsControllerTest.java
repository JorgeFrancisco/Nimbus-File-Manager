package br.com.jorgemelo.nimbusfilemanager.diagnostics.infrastructure.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import br.com.jorgemelo.nimbusfilemanager.diagnostics.application.DiagnosticsBundleService;
import br.com.jorgemelo.nimbusfilemanager.diagnostics.application.dto.DiagnosticsBundle;

class DiagnosticsControllerTest {

	private final DiagnosticsBundleService diagnosticsBundleService = mock(DiagnosticsBundleService.class);

	/**
	 * The archive has to arrive as a download with its own name: opened in the
	 * browser instead of saved, a support bundle is a wall of text nobody can
	 * forward.
	 */
	@Test
	void exportShouldStreamTheArchiveAsAnAttachment() {
		when(diagnosticsBundleService.export())
				.thenReturn(new DiagnosticsBundle("nimbus-diagnostics-20260801-060000.zip", "application/zip", _ -> {
				}));

		ResponseEntity<StreamingResponseBody> response = new DiagnosticsController(diagnosticsBundleService).export();

		Assertions.assertThat(response.getStatusCode().value()).isEqualTo(200);
		Assertions.assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
				.isEqualTo("attachment; filename=nimbus-diagnostics-20260801-060000.zip");
		Assertions.assertThat(response.getHeaders().getContentType()).hasToString("application/zip");
		Assertions.assertThat(response.getBody()).isNotNull();
	}
}