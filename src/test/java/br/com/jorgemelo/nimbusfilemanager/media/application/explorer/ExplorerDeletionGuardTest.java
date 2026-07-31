package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * The explorer browses and previews anywhere on disk, which is harmless for
 * reading. Writing is not: these checks pin the boundary that keeps a delete or
 * a rename inside the monitored library, so a mistyped path cannot reach the
 * rest of the machine.
 *
 * <p>
 * Messages resolve against the real bundle rather than a stub, so a refusal
 * whose key was never translated fails here instead of reaching a user as a raw
 * key.
 */
class ExplorerDeletionGuardTest {

	private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

	private final AppSettingService appSettingService = mock(AppSettingService.class);
	private final MessageSource messages = messageSource();

	private ExplorerDeletionGuard guard(String library) {
		when(appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(library);

		ExplorerDeletionGuard guard = new ExplorerDeletionGuard(appSettingService);

		guard.setMessageSource(messages);

		return guard;
	}

	private String expected(String key, Object... arguments) {
		return messages.getMessage(key, arguments, PT_BR);
	}

	/**
	 * The component under test resolves through LocaleContextHolder, so without
	 * pinning the language these assertions would compare pt-BR text against
	 * whatever the machine defaults to - green here and red on an English CI
	 * runner, which is exactly what happened.
	 */
	@BeforeEach
	void useThePortugueseBundle() {
		LocaleContextHolder.setLocale(PT_BR);
	}

	@AfterEach
	void releaseTheLocale() {
		LocaleContextHolder.resetLocaleContext();
	}

	@Test
	void allowsAFileInsideTheLibrary(@TempDir Path library) throws IOException {
		Path file = Files.createFile(library.resolve("photo.jpg"));

		Assertions.assertThat(guard(library.toString()).refusal(file)).isEmpty();
	}

	@Test
	void refusesAPathOutsideTheLibrary(@TempDir Path library, @TempDir Path elsewhere) throws IOException {
		Path outside = Files.createFile(elsewhere.resolve("payroll.xlsx"));

		Assertions.assertThat(guard(library.toString()).refusal(outside))
				.contains(expected("backend.files.outsideLibrary", PathUtils.normalize(library)));
	}

	/**
	 * Emptying the monitored folder itself is never what a click on a card meant,
	 * so the root is refused even though it is trivially "inside" the library.
	 */
	@Test
	void refusesTheLibraryRootItself(@TempDir Path library) {
		Assertions.assertThat(guard(library.toString()).refusal(library))
				.contains(expected("backend.files.outsideLibrary", PathUtils.normalize(library)));
	}

	@Test
	void refusesAPathThatIsNoLongerOnDisk(@TempDir Path library) {
		Assertions.assertThat(guard(library.toString()).refusal(library.resolve("gone.jpg")))
				.contains(expected("backend.files.pathGone"));
	}

	/**
	 * With no library configured there is no boundary to check against, so nothing
	 * may be destroyed - and the refusal says so instead of falling through.
	 */
	@Test
	void refusesEverythingWhileTheLibraryIsUnconfigured(@TempDir Path library) throws IOException {
		Path file = Files.createFile(library.resolve("photo.jpg"));

		Assertions.assertThat(guard("").refusal(file)).contains(expected("backend.files.libraryNotConfigured"));
	}

	private MessageSource messageSource() {
		ResourceBundleMessageSource source = new ResourceBundleMessageSource();

		source.setBasename("messages");
		source.setDefaultEncoding("UTF-8");
		source.setFallbackToSystemLocale(false);

		return source;
	}
}