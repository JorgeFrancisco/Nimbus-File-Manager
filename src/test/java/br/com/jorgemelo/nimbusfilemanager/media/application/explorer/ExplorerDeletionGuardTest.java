package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
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
 * The refusal is a code now, because the same question is asked by the worker,
 * where there is no request and no language. Every assertion still resolves it
 * against the real bundle: a refusal whose key was never translated fails here
 * instead of reaching a user as a raw key.
 */
class ExplorerDeletionGuardTest {

	private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

	private final AppSettingService appSettingService = mock(AppSettingService.class);
	private final MessageSource messages = messageSource();

	private ExplorerDeletionGuard guard(String library) {
		when(appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(library);

		return new ExplorerDeletionGuard(appSettingService);
	}

	private String expected(String key, Object... arguments) {
		return messages.getMessage(key, arguments, PT_BR);
	}

	/** The refusal as the sentence a screen would show, or null when allowed. */
	private String sentence(Optional<ExecutionMessage> refusal) {
		return refusal.map(message -> messages.getMessage(message.code(), message.args().toArray(), PT_BR))
				.orElse(null);
	}

	@Test
	void allowsAFileInsideTheLibrary(@TempDir Path library) throws IOException {
		Path file = Files.createFile(library.resolve("photo.jpg"));

		Assertions.assertThat(guard(library.toString()).refusal(file)).isEmpty();
	}

	@Test
	void refusesAPathOutsideTheLibrary(@TempDir Path library, @TempDir Path elsewhere) throws IOException {
		Path outside = Files.createFile(elsewhere.resolve("payroll.xlsx"));

		Assertions.assertThat(sentence(guard(library.toString()).refusal(outside)))
				.isEqualTo(expected("backend.files.outsideLibrary", PathUtils.normalize(library)));
	}

	/**
	 * Emptying the monitored folder itself is never what a click on a card meant,
	 * so the root is refused even though it is trivially "inside" the library.
	 */
	@Test
	void refusesTheLibraryRootItself(@TempDir Path library) {
		Assertions.assertThat(sentence(guard(library.toString()).refusal(library)))
				.isEqualTo(expected("backend.files.outsideLibrary", PathUtils.normalize(library)));
	}

	@Test
	void refusesAPathThatIsNoLongerOnDisk(@TempDir Path library) {
		Assertions.assertThat(sentence(guard(library.toString()).refusal(library.resolve("gone.jpg"))))
				.isEqualTo(expected("backend.files.pathGone"));
	}

	/**
	 * A shortcut is not the file it points at, and acting on one would act on
	 * something the user never selected - possibly outside the library entirely.
	 */
	@Test
	void refusesAShortcutBecauseItIsNotAPhysicalFile(@TempDir Path library) throws IOException {
		Path shortcut = Files.createFile(library.resolve("photo.lnk"));

		Assertions.assertThat(sentence(guard(library.toString()).refusal(shortcut)))
				.isEqualTo(expected("backend.files.notPhysical"));
	}

	/**
	 * With no library configured there is no boundary to check against, so nothing
	 * may be destroyed - and the refusal says so instead of falling through.
	 */
	@Test
	void refusesEverythingWhileTheLibraryIsUnconfigured(@TempDir Path library) throws IOException {
		Path file = Files.createFile(library.resolve("photo.jpg"));

		Assertions.assertThat(sentence(guard("").refusal(file)))
				.isEqualTo(expected("backend.files.libraryNotConfigured"));
	}

	private MessageSource messageSource() {
		ResourceBundleMessageSource source = new ResourceBundleMessageSource();

		source.setBasename("messages");
		source.setDefaultEncoding("UTF-8");
		source.setFallbackToSystemLocale(false);

		return source;
	}
}