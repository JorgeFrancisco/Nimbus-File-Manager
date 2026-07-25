package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.LocaleResolver;

import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Resolves the UI locale (pt-BR by default, English as the only alternative)
 * with the usual professional precedence:
 * <ol>
 * <li>the signed-in user's saved preference ({@link UserPagePreferenceService})
 * - the strongest signal for an authenticated user, kept per account across
 * sessions;</li>
 * <li>a previously chosen language remembered in a cookie (also covers the
 * login page, before any user is known);</li>
 * <li>the browser's {@code Accept-Language} header;</li>
 * <li>the application default, Brazilian Portuguese.</li>
 * </ol>
 * Switching (via {@code ?lang=}) saves the choice to the account when signed in
 * and always drops the cookie, so it survives logout/login and takes effect
 * without a restart.
 */
public class UserPreferenceLocaleResolver implements LocaleResolver {

	static final String LANGUAGE_KEY = "language";
	static final String LOCALE_COOKIE = "MM_LOCALE";
	static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("pt-BR");

	/** The two supported languages; anything else is normalized to the default. */
	static final List<Locale> SUPPORTED_LOCALES = List.of(DEFAULT_LOCALE, Locale.ENGLISH);

	private static final int COOKIE_MAX_AGE_SECONDS = 60 * 60 * 24 * 365;
	private static final String REQUEST_ATTRIBUTE = UserPreferenceLocaleResolver.class.getName() + ".RESOLVED";

	private final UserPagePreferenceService userPagePreferenceService;

	UserPreferenceLocaleResolver(UserPagePreferenceService userPagePreferenceService) {
		this.userPagePreferenceService = userPagePreferenceService;
	}

	@Override
	public Locale resolveLocale(HttpServletRequest request) {
		Object cached = request.getAttribute(REQUEST_ATTRIBUTE);

		if (cached instanceof Locale locale) {
			return locale;
		}

		Locale locale = resolve(request);

		request.setAttribute(REQUEST_ATTRIBUTE, locale);

		return locale;
	}

	private Locale resolve(HttpServletRequest request) {
		Locale saved = savedUserLocale();

		if (saved != null) {
			return saved;
		}

		Locale cookie = cookieLocale(request);

		if (cookie != null) {
			return cookie;
		}

		Locale header = acceptLanguageLocale(request);

		if (header != null) {
			return header;
		}

		return DEFAULT_LOCALE;
	}

	@Override
	public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
		Locale supported = toSupported(locale);

		String username = currentUsername();

		if (username != null) {
			userPagePreferenceService.save(username, SharedConstants.APP_PAGE_KEY, LANGUAGE_KEY,
					supported.toLanguageTag());
		}

		if (response != null) {
			Cookie cookie = new Cookie(LOCALE_COOKIE, supported.toLanguageTag());

			cookie.setPath("/");
			cookie.setMaxAge(COOKIE_MAX_AGE_SECONDS);
			cookie.setHttpOnly(true);
			// Secure when served over HTTPS; stays usable over plain HTTP (local-first
			// default at http://localhost), where request.isSecure() is false.
			cookie.setSecure(request.isSecure());

			response.addCookie(cookie);
		}

		// Apply to the current request too, so the very response that switched renders
		// in the new language.
		request.setAttribute(REQUEST_ATTRIBUTE, supported);
	}

	/**
	 * The saved preference for the signed-in user, or {@code null} when anonymous
	 * or nothing is saved.
	 */
	private Locale savedUserLocale() {
		String username = currentUsername();

		if (username == null) {
			return null;
		}

		Map<String, String> preferences = userPagePreferenceService.find(username, SharedConstants.APP_PAGE_KEY);

		String saved = preferences == null ? null : preferences.get(LANGUAGE_KEY);

		return supportedOrNull(saved);
	}

	private Locale cookieLocale(HttpServletRequest request) {
		if (request.getCookies() == null) {
			return null;
		}

		for (Cookie cookie : request.getCookies()) {
			if (LOCALE_COOKIE.equals(cookie.getName())) {
				return supportedOrNull(cookie.getValue());
			}
		}

		return null;
	}

	/**
	 * Only honors a real browser preference (header present); absent header falls
	 * through to the default.
	 */
	private Locale acceptLanguageLocale(HttpServletRequest request) {
		String header = request.getHeader("Accept-Language");

		if (header == null || header.isBlank()) {
			return null;
		}

		Locale requested = request.getLocale();

		return requested != null && "en".equals(requested.getLanguage()) ? Locale.ENGLISH : DEFAULT_LOCALE;
	}

	/**
	 * Maps a supported tag to its Locale, or {@code null} for an unknown/blank
	 * value.
	 */
	private Locale supportedOrNull(String languageTag) {
		if (languageTag == null || languageTag.isBlank()) {
			return null;
		}

		Locale locale = Locale.forLanguageTag(languageTag);

		if ("en".equals(locale.getLanguage())) {
			return Locale.ENGLISH;
		}

		return "pt".equals(locale.getLanguage()) ? DEFAULT_LOCALE : null;
	}

	/**
	 * Only pt-BR and English are offered; every other value collapses to the
	 * default.
	 */
	private Locale toSupported(Locale locale) {
		return locale != null && "en".equals(locale.getLanguage()) ? Locale.ENGLISH : DEFAULT_LOCALE;
	}

	private String currentUsername() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

		if (authentication == null || !authentication.isAuthenticated()
				|| "anonymousUser".equals(authentication.getName())) {
			return null;
		}

		return authentication.getName();
	}
}