package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;

import br.com.jorgemelo.nimbusfilemanager.backup.infrastructure.web.RestoreInProgressInterceptor;
import br.com.jorgemelo.nimbusfilemanager.security.application.PasswordChangeRequiredInterceptor;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web.LibraryConfigurationInterceptor;

/**
 * Covers the interaction between the two interceptors registered in
 * {@link WebMvcConfig}.
 *
 * <p>
 * Regression: with the databases wiped, the admin's first login had
 * {@code passwordChangeRequired=true} and an empty monitored folder. The
 * {@code libraryConfigurationInterceptor} redirected {@code /app/account} to
 * {@code /app/onboarding}, and the {@code passwordChangeRequiredInterceptor}
 * redirected {@code /app/onboarding} back to
 * {@code /app/account?passwordChangeRequired=true}, causing
 * ERR_TOO_MANY_REDIRECTS. The unit tests for each interceptor did not catch
 * this because the defect was in the {@code excludePathPatterns} configuration.
 */
class WebMvcConfigTest {

	private final RestoreInProgressInterceptor restoreInterceptor = mock(RestoreInProgressInterceptor.class);
	private final LibraryConfigurationInterceptor libraryInterceptor = mock(LibraryConfigurationInterceptor.class);
	private final PasswordChangeRequiredInterceptor passwordInterceptor = mock(PasswordChangeRequiredInterceptor.class);

	private MappedInterceptor libraryMapping;
	private MappedInterceptor passwordMapping;

	@BeforeEach
	void registerInterceptors() {
		WebMvcConfig config = new WebMvcConfig(restoreInterceptor, libraryInterceptor, passwordInterceptor);

		InterceptorRegistry registry = new InterceptorRegistry();

		config.addInterceptors(registry);

		List<Object> registered = ReflectionTestUtils.invokeMethod(registry, "getInterceptors");

		assertThat(registered).isNotNull();

		for (Object entry : registered) {
			MappedInterceptor mapped = (MappedInterceptor) entry;

			if (mapped.getInterceptor() == libraryInterceptor) {
				libraryMapping = mapped;
			} else if (mapped.getInterceptor() == passwordInterceptor) {
				passwordMapping = mapped;
			}
		}

		assertThat(libraryMapping).as("library interceptor mapping").isNotNull();
		assertThat(passwordMapping).as("password interceptor mapping").isNotNull();
	}

	@Test
	void libraryInterceptorSkipsAccountRoutesToBreakRedirectLoop() {
		assertThat(matches(libraryMapping, "/app/account")).isFalse();
		assertThat(matches(libraryMapping, "/app/account/password")).isFalse();
		assertThat(matches(libraryMapping, "/app/account/2fa/qrcode")).isFalse();
	}

	@Test
	void libraryInterceptorStillGuardsOtherAppRoutes() {
		assertThat(matches(libraryMapping, "/app/files")).isTrue();
		assertThat(matches(libraryMapping, "/app/organization")).isTrue();
		assertThat(matches(libraryMapping, "/app/onboarding")).isFalse();
	}

	@Test
	void libraryInterceptorSkipsFolderBrowserSoOnboardingCanPickAFolder() {
		// The folder picker calls /app/settings/folders while the watch folder is
		// still unset (onboarding); it must not be bounced back to /app/onboarding.
		assertThat(matches(libraryMapping, "/app/settings/folders")).isFalse();
	}

	@Test
	void passwordChangeInterceptorSkipsAccountRoutes() {
		assertThat(matches(passwordMapping, "/app/account")).isFalse();
		assertThat(matches(passwordMapping, "/app/account/password")).isFalse();
	}

	@Test
	void passwordChangeInterceptorStillGuardsOnboardingAndOtherRoutes() {
		assertThat(matches(passwordMapping, "/app/onboarding")).isTrue();
		assertThat(matches(passwordMapping, "/app/files")).isTrue();
	}

	@Test
	void accountRedirectTargetIsNotReinterceptedIntoALoop() {
		// Target of the passwordChangeRequiredInterceptor: no interceptor may
		// redirect it again, otherwise the loop comes back.
		assertThat(matches(libraryMapping, "/app/account")).isFalse();
		assertThat(matches(passwordMapping, "/app/account")).isFalse();
	}

	private boolean matches(MappedInterceptor mapped, String uri) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);

		ServletRequestPathUtils.parseAndCache(request);

		return mapped.matches(request);
	}
}