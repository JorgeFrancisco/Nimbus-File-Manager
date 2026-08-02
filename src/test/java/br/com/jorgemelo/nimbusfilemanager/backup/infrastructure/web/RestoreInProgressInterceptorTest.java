package br.com.jorgemelo.nimbusfilemanager.backup.infrastructure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import br.com.jorgemelo.nimbusfilemanager.backup.application.CatalogBackupAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.BackupSnapshot;
import br.com.jorgemelo.nimbusfilemanager.backup.domain.enums.BackupPhase;

/**
 * What the screens get while the catalog is being replaced.
 *
 * <p>
 * The first restore that ran with a browser open returned a 500 naming a table
 * that did not exist - {@code pg_restore --clean} had dropped it and was about
 * to recreate it. Anything that renders in that window can hit the same, so the
 * request is answered before it reaches a handler.
 */
class RestoreInProgressInterceptorTest {

	private final CatalogBackupAsyncRunner runner = mock(CatalogBackupAsyncRunner.class);

	private final RestoreInProgressInterceptor interceptor = new RestoreInProgressInterceptor(runner);

	@Test
	void holdsAScreenOffWhileTheCatalogIsBeingReplaced() throws Exception {
		restoring(120);

		MockHttpServletResponse response = new MockHttpServletResponse();

		boolean proceed = interceptor.preHandle(new MockHttpServletRequest("GET", "/app/timeline"), response, null);

		Assertions.assertThat(proceed).isFalse();
		Assertions.assertThat(response.getStatus()).isEqualTo(503);
		Assertions.assertThat(response.getHeader("Retry-After")).isEqualTo("3");
		Assertions.assertThat(response.getContentAsString()).contains("Restaurando o catálogo").contains("120");
	}

	/** A caller that is not a browser gets the reason, not a page. */
	@Test
	void answersTheApiWithTextInsteadOfAPage() throws Exception {
		restoring(0);

		MockHttpServletResponse response = new MockHttpServletResponse();

		interceptor.preHandle(new MockHttpServletRequest("GET", "/api/catalog/files"), response, null);

		Assertions.assertThat(response.getContentType()).startsWith("text/plain");
		Assertions.assertThat(response.getContentAsString()).doesNotContain("<html");
	}

	/**
	 * A backup only reads the database, so nothing is missing while it runs and
	 * holding the screens off would be a false alarm.
	 */
	@Test
	void letsEverythingThroughWhileOnlyABackupIsRunning() throws Exception {
		when(runner.isRunning()).thenReturn(true);
		when(runner.progress()).thenReturn(new BackupSnapshot(BackupPhase.EXPORTING, 10));

		Assertions.assertThat(interceptor.preHandle(new MockHttpServletRequest("GET", "/app/timeline"),
				new MockHttpServletResponse(), null)).isTrue();
	}

	@Test
	void letsEverythingThroughWhenNothingIsRunning() throws Exception {
		when(runner.isRunning()).thenReturn(false);

		Assertions.assertThat(interceptor.preHandle(new MockHttpServletRequest("GET", "/app/timeline"),
				new MockHttpServletResponse(), null)).isTrue();
	}

	private void restoring(long megabytes) {
		when(runner.isRunning()).thenReturn(true);
		when(runner.progress())
				.thenReturn(new BackupSnapshot(BackupPhase.IMPORTING, megabytes * 1048576));
	}
}