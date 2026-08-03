package br.com.jorgemelo.nimbusfilemanager.shared.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * Resolves user-facing backend messages in the locale of the current request.
 *
 * <p>
 * Message text lives exclusively in the {@code messages*.properties} bundles;
 * there are no hard-coded fallback strings. When a Spring {@link MessageSource}
 * is available (the normal runtime and full-context tests) messages are
 * resolved in the current request locale. In plain unit construction, where no
 * {@code MessageSource} is injected, the Brazilian Portuguese base bundle
 * ({@code messages.properties}) is read directly, so the same text is reused
 * without duplicating it in code.
 *
 * <p>
 * A key missing from the bundle raises {@link NoSuchMessageException}.
 * {@code BackendMessageKeysTest} guarantees at build time that every key used
 * in code exists in every bundle, so a missing key breaks the build instead of
 * ever reaching a user.
 */
public abstract class LocalizedComponent {

	private static final String BASE_BUNDLE = "/messages.properties";

	private static final AtomicReference<Properties> baseBundle = new AtomicReference<>();

	private MessageSource messageSource;

	/**
	 * Optional setter injection: in a Spring context the framework supplies the
	 * {@link MessageSource}; in plain unit construction the setter is never called,
	 * so {@code messageSource} stays {@code null} and messages fall back to the
	 * base bundle. Setter injection is the idiomatic Spring pattern for an optional
	 * dependency and keeps this dual-mode behaviour without field injection.
	 */
	@Autowired(required = false)
	public void setMessageSource(MessageSource messageSource) {
		this.messageSource = messageSource;
	}

	protected String message(String key, Object... arguments) {
		if (messageSource != null) {
			return messageSource.getMessage(key, arguments, LocaleContextHolder.getLocale());
		}

		return resolveFromBaseBundle(key, arguments);
	}

	private static String resolveFromBaseBundle(String key, Object... arguments) {
		String template = baseBundle().getProperty(key);

		if (template == null) {
			throw new NoSuchMessageException(key);
		}

		String resolved = template;

		for (int index = 0; index < arguments.length; index++) {
			resolved = resolved.replace("{" + index + "}", String.valueOf(arguments[index]));
		}

		return resolved;
	}

	private static Properties baseBundle() {
		Properties local = baseBundle.get();

		if (local == null) {
			synchronized (LocalizedComponent.class) {
				local = baseBundle.get();

				if (local == null) {
					local = loadBaseBundle();
					baseBundle.set(local);
				}
			}
		}

		return local;
	}

	private static Properties loadBaseBundle() {
		Properties properties = new Properties();

		try (InputStream stream = LocalizedComponent.class.getResourceAsStream(BASE_BUNDLE)) {
			if (stream == null) {
				throw new IllegalStateException("Bundle base ausente no classpath: " + BASE_BUNDLE);
			}

			try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				properties.load(reader);
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Falha ao carregar o bundle base " + BASE_BUNDLE, exception);
		}

		return properties;
	}
}