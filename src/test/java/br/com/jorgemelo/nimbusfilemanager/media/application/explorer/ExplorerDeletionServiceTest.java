package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;

import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLock;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.ExplorerActionResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantineIntakeService;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.IntakeOutcome;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * The destructive half of the explorer menu. Quarantine has to refuse rather
 * than move a file the catalog cannot record - an unrecorded file in quarantine
 * could never be restored - and a permanent delete has to leave the catalog
 * consistent with a disk that no longer holds the file.
 */
class ExplorerDeletionServiceTest {

	private final ExplorerDeletionGuard guard = mock(ExplorerDeletionGuard.class);
	private final QuarantineIntakeService quarantineIntakeService = mock(QuarantineIntakeService.class);
	private final CatalogFileRepository catalogFileRepository = mock(CatalogFileRepository.class);
	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
	private final OperationLockService operationLockService = mock(OperationLockService.class);
	private final MessageSource messages = messageSource();

	private ExplorerDeletionService service() {
		return service(new DefaultExplorerFileSystem(new SelfWrittenPathRegistry(Clock.systemUTC())));
	}

	/**
	 * The filesystem is a parameter so a test can hand over a disk that refuses:
	 * unreadable folders and undeletable files are branches of this service that no
	 * temporary directory can be made to produce on demand.
	 */
	private ExplorerDeletionService service(ExplorerFileSystem fileSystem) {
		when(guard.refusal(any())).thenReturn(Optional.empty());
		when(operationLockService.acquireWithin(any(), any(), any())).thenReturn(mock(OperationLock.class));
		when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		ExplorerDeletionService service = new ExplorerDeletionService(guard, quarantineIntakeService,
				catalogFileRepository, executionRepository, operationLockService,
				Clock.fixed(Instant.parse("2026-07-31T10:00:00Z"), ZoneOffset.UTC), fileSystem);

		service.setMessageSource(messages);

		return service;
	}

	private String expected(String key, Object... arguments) {
		return messages.getMessage(key, arguments, Locale.forLanguageTag("pt-BR"));
	}

	@Test
	void refusesWhatTheGuardRefuses(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		ExplorerDeletionService service = service();

		when(guard.refusal(any())).thenReturn(Optional.of("nope"));

		Assertions.assertThat(service.quarantine(file).message()).isEqualTo("nope");
		Assertions.assertThat(service.deletePermanently(file).message()).isEqualTo("nope");
		Assertions.assertThat(file).exists();
	}

	@Test
	void refusesQuarantineWhileTheQuarantineFolderIsUnset(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		when(quarantineIntakeService.root()).thenReturn(Optional.empty());

		Assertions.assertThat(service().quarantine(file).message())
				.isEqualTo(expected("backend.files.quarantineNotConfigured"));
	}

	/**
	 * Moving a file the catalog never saw would put it somewhere no restore can
	 * find it, so it stays where it is and the message explains why.
	 */
	@Test
	void refusesQuarantineWhenNothingUnderThePathIsCataloged(@TempDir Path folder, @TempDir Path quarantine)
			throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		when(quarantineIntakeService.root()).thenReturn(Optional.of(quarantine));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());

		Assertions.assertThat(service().quarantine(file).message())
				.isEqualTo(expected("backend.files.quarantineNothingCataloged"));
		Assertions.assertThat(file).exists();

		verify(quarantineIntakeService, never()).intake(any(), any(), any(), any());
	}

	@Test
	void sendsACatalogedFileThroughTheSameIntakeTheDuplicateScreenUses(@TempDir Path folder, @TempDir Path quarantine)
			throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		CatalogFile stored = CatalogFile.builder().fileKey(PathUtils.normalize(file)).build();

		when(quarantineIntakeService.root()).thenReturn(Optional.of(quarantine));
		when(catalogFileRepository.findByFileKey(PathUtils.normalize(file))).thenReturn(Optional.of(stored));
		when(quarantineIntakeService.intake(any(), any(), any(), any())).thenReturn(IntakeOutcome.MOVED);

		ExplorerActionResult result = service().quarantine(file);

		Assertions.assertThat(result.success()).isTrue();
		Assertions.assertThat(result.processed()).isEqualTo(1);

		verify(quarantineIntakeService).intake(any(Execution.class), any(CatalogFile.class), any(Path.class), any());
	}

	@Test
	void deletesAFileFromDiskAndMarksTheCatalogEntry(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		CatalogFile stored = CatalogFile.builder().fileKey(PathUtils.normalize(file)).build();

		when(catalogFileRepository.findByFileKey(PathUtils.normalize(file))).thenReturn(Optional.of(stored));

		ExplorerActionResult result = service().deletePermanently(file);

		Assertions.assertThat(result.success()).isTrue();
		Assertions.assertThat(result.message()).isEqualTo(expected("backend.files.deleteDone", 1));
		Assertions.assertThat(file).doesNotExist();
		Assertions.assertThat(stored.getLifecycleStatus()).isEqualTo(LifecycleStatus.DELETED);

		verify(catalogFileRepository).saveAll(List.of(stored));
	}

	/**
	 * A folder goes with everything under it, counted by files so the message can
	 * say how much was actually erased.
	 */
	@Test
	void deletesAFolderWithEverythingUnderIt(@TempDir Path parent) throws IOException {
		Path folder = Files.createDirectory(parent.resolve("album"));
		Path nested = Files.createDirectory(folder.resolve("2008"));

		Files.createFile(folder.resolve("a.jpg"));
		Files.createFile(nested.resolve("b.jpg"));

		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());

		ExplorerActionResult result = service().deletePermanently(folder);

		Assertions.assertThat(result.message()).isEqualTo(expected("backend.files.deleteDone", 2));
		Assertions.assertThat(folder).doesNotExist();
	}

	/**
	 * A folder is quarantined file by file, and once the last one has gone the empty
	 * container is removed too - leaving it behind would show an empty folder that
	 * the user believes they deleted.
	 */
	@Test
	void quarantinesEveryCatalogedFileUnderAFolderAndRemovesTheEmptyFolder(@TempDir Path parent,
			@TempDir Path quarantine) throws IOException {
		Path folder = Files.createDirectory(parent.resolve("album"));
		Path first = Files.createFile(folder.resolve("a.jpg"));
		Path second = Files.createFile(folder.resolve("b.jpg"));

		when(quarantineIntakeService.root()).thenReturn(Optional.of(quarantine));
		when(catalogFileRepository.findByFileKey(PathUtils.normalize(first)))
				.thenReturn(Optional.of(CatalogFile.builder().fileKey(PathUtils.normalize(first)).build()));
		when(catalogFileRepository.findByFileKey(PathUtils.normalize(second)))
				.thenReturn(Optional.of(CatalogFile.builder().fileKey(PathUtils.normalize(second)).build()));
		when(quarantineIntakeService.intake(any(), any(), any(), any())).thenAnswer(invocation -> {
			// The real intake moves the file out; mirroring that here is what lets the
			// service find the folder empty afterwards.
			Files.deleteIfExists(Path.of(((CatalogFile) invocation.getArgument(1)).getFileKey()));

			return IntakeOutcome.MOVED;
		});

		ExplorerActionResult result = service().quarantine(folder);

		Assertions.assertThat(result.processed()).isEqualTo(2);
		Assertions.assertThat(folder).doesNotExist();
	}

	/**
	 * A file the intake could not move is counted as a failure, and the run is
	 * reported as unsuccessful rather than as a clean sweep.
	 */
	@Test
	void reportsAnIntakeFailureInsteadOfClaimingSuccess(@TempDir Path folder, @TempDir Path quarantine)
			throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		when(quarantineIntakeService.root()).thenReturn(Optional.of(quarantine));
		when(catalogFileRepository.findByFileKey(PathUtils.normalize(file)))
				.thenReturn(Optional.of(CatalogFile.builder().fileKey(PathUtils.normalize(file)).build()));
		when(quarantineIntakeService.intake(any(), any(), any(), any())).thenReturn(IntakeOutcome.ERROR);

		ExplorerActionResult result = service().quarantine(file);

		Assertions.assertThat(result.success()).isFalse();
		Assertions.assertThat(result.failed()).isEqualTo(1);
	}

	@Test
	void refusesWhileAnotherOperationHoldsThePath(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		ExplorerDeletionService service = service();

		when(operationLockService.acquireWithin(any(), any(), any())).thenThrow(new OperationLockException("busy"));
		when(quarantineIntakeService.root()).thenReturn(Optional.of(folder));

		Assertions.assertThat(service.quarantine(file).message()).isEqualTo(expected("backend.files.busy"));
		Assertions.assertThat(service.deletePermanently(file).message()).isEqualTo(expected("backend.files.busy"));
	}

	/**
	 * When the disk refuses the delete, the catalog must not be marked either: the
	 * file is still there, and a row saying otherwise would be a lie the next
	 * reconcile would have to undo.
	 */
	@Test
	void reportsAFailedDeleteAndLeavesTheCatalogAlone(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		ExplorerFileSystem refusing = mock(ExplorerFileSystem.class);

		when(refusing.deleteRecursively(any())).thenThrow(new IOException("in use"));
		when(catalogFileRepository.findByFileKey(any()))
				.thenReturn(Optional.of(CatalogFile.builder().fileKey(PathUtils.normalize(file)).build()));

		ExplorerActionResult result = service(refusing).deletePermanently(file);

		Assertions.assertThat(result.success()).isFalse();
		Assertions.assertThat(result.message()).isEqualTo(expected("backend.files.deleteFailed", "in use"));

		verify(catalogFileRepository, never()).saveAll(any());
	}

	/**
	 * A folder that cannot be listed yields no candidates, so the run refuses
	 * instead of reporting that it quarantined nothing successfully.
	 */
	@Test
	void refusesQuarantineWhenTheFolderCannotBeListed(@TempDir Path folder, @TempDir Path quarantine)
			throws IOException {
		ExplorerFileSystem refusing = mock(ExplorerFileSystem.class);

		when(refusing.isDirectory(any())).thenReturn(true);
		when(refusing.listFiles(any())).thenThrow(new IOException("permission denied"));
		when(quarantineIntakeService.root()).thenReturn(Optional.of(quarantine));

		Assertions.assertThat(service(refusing).quarantine(folder).message())
				.isEqualTo(expected("backend.files.quarantineNothingCataloged"));
	}

	/**
	 * Failing to remove the emptied folder is not worth failing the quarantine over:
	 * every file already moved, and the leftover container is reported in the log
	 * rather than turned into an error the user cannot act on.
	 */
	@Test
	void keepsTheQuarantineSuccessfulWhenTheEmptiedFolderCannotBeRemoved(@TempDir Path folder,
			@TempDir Path quarantine) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		ExplorerFileSystem refusing = mock(ExplorerFileSystem.class);

		when(refusing.isDirectory(folder)).thenReturn(true);
		when(refusing.listFiles(any())).thenReturn(List.of(file));
		doThrow(new IOException("not empty")).when(refusing).deleteEmptyTree(any());
		when(quarantineIntakeService.root()).thenReturn(Optional.of(quarantine));
		when(catalogFileRepository.findByFileKey(PathUtils.normalize(file)))
				.thenReturn(Optional.of(CatalogFile.builder().fileKey(PathUtils.normalize(file)).build()));
		when(quarantineIntakeService.intake(any(), any(), any(), any())).thenReturn(IntakeOutcome.MOVED);

		ExplorerActionResult result = service(refusing).quarantine(folder);

		Assertions.assertThat(result.success()).isTrue();
		Assertions.assertThat(result.message()).isEqualTo(expected("backend.files.quarantineDoneFolderKept", 1));
	}

	/**
	 * Quarantining a folder has to take the folder with it, subfolders included.
	 * Removing only the top folder when it happened to be flat left the whole
	 * skeleton on screen, and the user who asked to delete an album still saw it
	 * listed - empty, but there.
	 */
	@Test
	void quarantiningAFolderTakesItsSubfoldersWithIt(@TempDir Path parent, @TempDir Path quarantine)
			throws IOException {
		Path album = Files.createDirectory(parent.resolve("album"));
		Path year = Files.createDirectory(album.resolve("2008"));
		Path deeper = Files.createDirectory(year.resolve("praia"));
		Path photo = Files.createFile(deeper.resolve("a.jpg"));

		when(quarantineIntakeService.root()).thenReturn(Optional.of(quarantine));
		when(catalogFileRepository.findByFileKey(PathUtils.normalize(photo)))
				.thenReturn(Optional.of(CatalogFile.builder().fileKey(PathUtils.normalize(photo)).build()));
		when(quarantineIntakeService.intake(any(), any(), any(), any())).thenAnswer(invocation -> {
			Files.deleteIfExists(Path.of(((CatalogFile) invocation.getArgument(1)).getFileKey()));

			return IntakeOutcome.MOVED;
		});

		Assertions.assertThat(service().quarantine(album).success()).isTrue();
		Assertions.assertThat(album).doesNotExist();
		Assertions.assertThat(parent).exists();
	}

	/**
	 * A file left behind - one the catalog never saw - keeps the whole folder in
	 * place: deleting the tree around it would strand the file or lose it.
	 */
	@Test
	void keepsTheFolderWhenAFileStaysBehind(@TempDir Path parent, @TempDir Path quarantine) throws IOException {
		Path album = Files.createDirectory(parent.resolve("album"));
		Path known = Files.createFile(album.resolve("known.jpg"));

		Files.createFile(album.resolve("unknown.jpg"));

		when(quarantineIntakeService.root()).thenReturn(Optional.of(quarantine));
		when(catalogFileRepository.findByFileKey(PathUtils.normalize(known)))
				.thenReturn(Optional.of(CatalogFile.builder().fileKey(PathUtils.normalize(known)).build()));
		when(catalogFileRepository.findByFileKey(any())).thenAnswer(invocation -> {
			String key = invocation.getArgument(0);

			return key.equals(PathUtils.normalize(known))
					? Optional.of(CatalogFile.builder().fileKey(key).build())
					: Optional.empty();
		});
		when(quarantineIntakeService.intake(any(), any(), any(), any())).thenAnswer(invocation -> {
			Files.deleteIfExists(Path.of(((CatalogFile) invocation.getArgument(1)).getFileKey()));

			return IntakeOutcome.MOVED;
		});

		service().quarantine(album);

		Assertions.assertThat(album).exists();
		Assertions.assertThat(album.resolve("unknown.jpg")).exists();
	}

	/**
	 * An empty folder is what a previous quarantine leaves behind, and refusing to
	 * remove it would strand the user with a folder they cannot delete from the very
	 * screen that emptied it. There is nothing to protect, so it just goes.
	 */
	@Test
	void removesAnEmptyFolderInsteadOfRefusingIt(@TempDir Path parent, @TempDir Path quarantine) throws IOException {
		Path album = Files.createDirectory(parent.resolve("album"));

		Files.createDirectory(album.resolve("2008"));

		when(quarantineIntakeService.root()).thenReturn(Optional.of(quarantine));

		ExplorerActionResult result = service().quarantine(album);

		Assertions.assertThat(result.success()).isTrue();
		Assertions.assertThat(result.message()).isEqualTo(expected("backend.files.emptyFolderRemoved"));
		Assertions.assertThat(album).doesNotExist();

		verify(quarantineIntakeService, never()).intake(any(), any(), any(), any());
	}

	/**
	 * The folder could not be removed, so the dialog must not claim it was. Saying
	 * "empty folder removed" while it sits on screen sends the user back to try the
	 * same thing again, which is exactly what happened with a read-only folder
	 * synced from a phone.
	 */
	@Test
	void refusesWhenTheEmptyFolderCannotBeRemoved(@TempDir Path parent, @TempDir Path quarantine) throws IOException {
		Path album = Files.createDirectory(parent.resolve("album"));

		ExplorerFileSystem refusing = mock(ExplorerFileSystem.class);

		when(refusing.isDirectory(any())).thenReturn(true);
		when(refusing.listFiles(any())).thenReturn(List.of());
		doThrow(new IOException("access denied")).when(refusing).deleteEmptyTree(any());
		when(quarantineIntakeService.root()).thenReturn(Optional.of(quarantine));

		ExplorerActionResult result = service(refusing).quarantine(album);

		Assertions.assertThat(result.success()).isFalse();
		Assertions.assertThat(result.message()).isEqualTo(expected("backend.files.folderNotRemoved"));
	}

	private MessageSource messageSource() {
		ResourceBundleMessageSource source = new ResourceBundleMessageSource();

		source.setBasename("messages");
		source.setDefaultEncoding("UTF-8");
		source.setFallbackToSystemLocale(false);

		return source;
	}
}