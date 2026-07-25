package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Locale;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import jakarta.servlet.http.Cookie;

class UserPreferenceLocaleResolverTest {

	private final UserPagePreferenceService preferences = mock(UserPagePreferenceService.class);
	private final UserPreferenceLocaleResolver resolver = new UserPreferenceLocaleResolver(preferences);

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void defaultsToBrazilianPortugueseForAnonymousRequestsWithNoCookieOrBrowserPreference() {
		Assertions.assertThat(resolver.resolveLocale(new MockHttpServletRequest()))
				.isEqualTo(UserPreferenceLocaleResolver.DEFAULT_LOCALE);
	}

	@Test
	void anonymousFallsBackToTheBrowserAcceptLanguageWhenNoCookieIsPresent() {
		MockHttpServletRequest request = new MockHttpServletRequest();

		request.addPreferredLocale(Locale.ENGLISH);

		Assertions.assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.ENGLISH);
	}

	@Test
	void anonymousPrefersTheRememberedCookieOverTheBrowserPreference() {
		MockHttpServletRequest request = new MockHttpServletRequest();

		request.addPreferredLocale(Locale.forLanguageTag("pt-BR"));
		request.setCookies(new Cookie(UserPreferenceLocaleResolver.LOCALE_COOKIE, "en"));

		Assertions.assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.ENGLISH);
	}

	@Test
	void appliesTheSavedEnglishPreferenceForTheSignedInUser() {
		authenticate("admin@example.com");

		when(preferences.find("admin@example.com", SharedConstants.APP_PAGE_KEY))
				.thenReturn(Map.of(UserPreferenceLocaleResolver.LANGUAGE_KEY, "en"));

		Assertions.assertThat(resolver.resolveLocale(new MockHttpServletRequest())).isEqualTo(Locale.ENGLISH);
	}

	@Test
	void fallsBackToTheDefaultWhenNothingIsSavedOrTheValueIsUnsupported() {
		authenticate("admin@example.com");

		when(preferences.find("admin@example.com", SharedConstants.APP_PAGE_KEY))
				.thenReturn(Map.of(UserPreferenceLocaleResolver.LANGUAGE_KEY, "fr"));

		Assertions.assertThat(resolver.resolveLocale(new MockHttpServletRequest()))
				.isEqualTo(UserPreferenceLocaleResolver.DEFAULT_LOCALE);
	}

	@Test
	void setLocalePersistsTheChoiceDropsACookieAndAppliesItToTheCurrentRequest() {
		authenticate("admin@example.com");

		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();

		resolver.setLocale(request, response, Locale.ENGLISH);

		verify(preferences).save("admin@example.com", SharedConstants.APP_PAGE_KEY,
				UserPreferenceLocaleResolver.LANGUAGE_KEY, "en");

		// A cookie remembers the choice for the login page and the next visit.
		Assertions.assertThat(response.getCookie(UserPreferenceLocaleResolver.LOCALE_COOKIE)).isNotNull();
		Assertions.assertThat(response.getCookie(UserPreferenceLocaleResolver.LOCALE_COOKIE).getValue())
				.isEqualTo("en");
		// The very request that switched already resolves to the new language (no
		// restart, no reload).
		Assertions.assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.ENGLISH);
	}

	private void authenticate(String username) {
		SecurityContextHolder.getContext()
				.setAuthentication(new TestingAuthenticationToken(username, "password", "ROLE_ADMIN"));
	}
}