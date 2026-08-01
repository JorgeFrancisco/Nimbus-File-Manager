package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLock;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.ExplorerActionResult;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureFileMove;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Renaming is the one explorer action that writes a new path into the catalog,
 * so what matters here is that a bad name never reaches the disk and that a
 * successful rename leaves the catalog pointing at the new file rather than at
 * a path that no longer exists.
 */
class ExplorerRenameServiceTest {

	private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

	private final ExplorerDeletionGuard guard = mock(ExplorerDeletionGuard.class);
	private final SecureFileMove secureFileMove = mock(SecureFileMove.class);
	private final CatalogFileRepository catalogFileRepository = mock(CatalogFileRepository.class);
	private final OperationLockService operationLockService = mock(OperationLockService.class);
	private final MessageSource messages = messageSource();

	private ExplorerRenameService service() {
		when(guard.refusal(any())).thenReturn(Optional.empty());
		when(operationLockService.acquireWithin(any(), any(), any())).thenReturn(mock(OperationLock.class));

		ExplorerRenameService service = new ExplorerRenameService(guard, secureFileMove, catalogFileRepository,
				operationLockService);

		service.setMessageSource(messages);

		return service;
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
	void refusesANameCarryingAPathSeparator(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		ExplorerActionResult result = service().rename(file, "../escaped.jpg");

		Assertions.assertThat(result.success()).isFalse();
		Assertions.assertThat(result.message()).isEqualTo(expected("backend.files.renameInvalidName"));

		verify(secureFileMove, never()).move(any(), any(), anyBoolean());
	}

	@Test
	void refusesABlankName(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		Assertions.assertThat(service().rename(file, "   ").message())
				.isEqualTo(expected("backend.files.renameInvalidName"));
	}

	@Test
	void refusesANullName(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		Assertions.assertThat(service().rename(file, null).message())
				.isEqualTo(expected("backend.files.renameInvalidName"));
	}

	/**
	 * A filesystem root has no parent to rename inside of. The guard already
	 * refuses it in production; this pins that the service does not dereference the
	 * missing parent even when asked directly.
	 */
	@Test
	void refusesRenamingSomethingWithoutAParent(@TempDir Path folder) {
		Assertions.assertThat(service().rename(folder.getRoot(), "novo").message())
				.isEqualTo(expected("backend.files.renameInvalidName"));
	}

	/**
	 * Overwriting the neighbour would destroy a file the user never selected, so
	 * the collision is refused by name instead of resolved silently.
	 */
	@Test
	void refusesWhenSomethingAlreadyHasTheTargetName(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		Files.createFile(folder.resolve("taken.jpg"));

		Assertions.assertThat(service().rename(file, "taken.jpg").message())
				.isEqualTo(expected("backend.files.renameTargetExists", "taken.jpg"));

		verify(secureFileMove, never()).move(any(), any(), anyBoolean());
	}

	@Test
	void movesTheFileSecurelyAndRepointsTheCatalog(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));
		Path renamed = folder.resolve("holiday.jpg");

		CatalogFile stored = CatalogFile.builder().fileKey(PathUtils.normalize(file)).fileName("photo.jpg")
				.extension("jpg").build();

		when(catalogFileRepository.findByFileKey(PathUtils.normalize(file))).thenReturn(Optional.of(stored));

		ExplorerActionResult result = service().rename(file, "holiday.jpg");

		Assertions.assertThat(result.success()).isTrue();
		Assertions.assertThat(result.message()).isEqualTo(expected("backend.files.renameDone", "holiday.jpg"));
		Assertions.assertThat(stored.getFileKey()).isEqualTo(PathUtils.normalize(renamed));
		Assertions.assertThat(stored.getFileName()).isEqualTo("holiday.jpg");

		verify(secureFileMove).move(file, renamed, false);
		verify(catalogFileRepository).save(stored);
	}

	/**
	 * A file the catalog never saw is still renamed on disk; there is simply no row
	 * to repoint, and that must not be mistaken for a failure.
	 */
	@Test
	void renamesAFileTheCatalogDoesNotKnow(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());

		Assertions.assertThat(service().rename(file, "holiday.jpg").success()).isTrue();

		verify(catalogFileRepository, never()).save(any());
	}

	/**
	 * A folder carries no bytes of its own to verify, so it is moved plainly; the
	 * catalog rows under it are left to the reconciliation rather than rewritten
	 * one by one here.
	 */
	@Test
	void renamesAFolderWithoutTheSecureMoveAndWithoutTouchingTheCatalog(@TempDir Path parent) throws IOException {
		Path folder = Files.createDirectory(parent.resolve("album"));

		Assertions.assertThat(service().rename(folder, "viagem").success()).isTrue();
		Assertions.assertThat(parent.resolve("viagem")).exists();
		Assertions.assertThat(folder).doesNotExist();

		verify(secureFileMove, never()).move(any(), any(), anyBoolean());
		verify(catalogFileRepository, never()).save(any());
	}

	/**
	 * The secure move refuses when it cannot guarantee the copy; the file stays put
	 * and the dialog says what went wrong instead of reporting a rename that never
	 * happened.
	 */
	@Test
	void reportsAFailedMoveInsteadOfClaimingSuccess(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		ExplorerRenameService service = service();

		doThrow(new IOException("disk full")).when(secureFileMove).move(any(), any(), anyBoolean());

		ExplorerActionResult result = service.rename(file, "holiday.jpg");

		Assertions.assertThat(result.success()).isFalse();
		Assertions.assertThat(result.message()).isEqualTo(expected("backend.files.renameFailed", "disk full"));
	}

	/**
	 * Another operation holding the path is a temporary refusal, not a failure: the
	 * message invites the user to try again once it finishes.
	 */
	@Test
	void refusesWhileAnotherOperationHoldsThePath(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		ExplorerRenameService service = service();

		when(operationLockService.acquireWithin(any(), any(), any())).thenThrow(new OperationLockException("busy"));

		Assertions.assertThat(service.rename(file, "holiday.jpg").message()).isEqualTo(expected("backend.files.busy"));
	}

	private MessageSource messageSource() {
		ResourceBundleMessageSource source = new ResourceBundleMessageSource();

		source.setBasename("messages");
		source.setDefaultEncoding("UTF-8");
		source.setFallbackToSystemLocale(false);

		return source;
	}
}