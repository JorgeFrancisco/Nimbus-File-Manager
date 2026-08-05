package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import static br.com.jorgemelo.nimbusfilemanager.media.application.explorer.CarriedMessages.carrying;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.stubbing.Answer;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.media.application.constants.ExplorerMessages;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationMoveVerifier;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureFileMove;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureLibraryFiles;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantineIntakeService;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.IntakeOutcome;
import br.com.jorgemelo.nimbusfilemanager.shared.application.InMemorySelfWrittenPaths;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * The destructive half of the explorer menu, off the queue. Quarantine has to
 * refuse rather than move a file the catalog cannot record - an unrecorded file
 * in quarantine could never be restored - and a permanent delete has to leave
 * the catalog consistent with a disk that no longer holds the file.
 *
 * <p>
 * Nothing here takes a lock: the dispatcher holds the paths before the handler
 * is called, which is why the row names both ends of the operation.
 */
class ExplorerDeletionServiceTest {

	private static final long EXECUTION_ID = 7L;

	private final ExplorerDeletionGuard guard = mock(ExplorerDeletionGuard.class);
	private final QuarantineIntakeService quarantineIntakeService = mock(QuarantineIntakeService.class);
	private final CatalogFileRepository catalogFileRepository = mock(CatalogFileRepository.class);
	private final ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);
	private final ExecutionOwnership ownership = mock(ExecutionOwnership.class);

	private ExplorerDeletionService service() {
		return service(new DefaultExplorerFileSystem(libraryFiles()));
	}

	/**
	 * The filesystem is a parameter so a test can hand over a disk that refuses:
	 * unreadable folders and undeletable files are branches of this service that no
	 * temporary directory can be made to produce on demand.
	 */
	private ExplorerDeletionService service(ExplorerFileSystem fileSystem) {
		when(guard.refusal(any())).thenReturn(Optional.empty());

		return new ExplorerDeletionService(guard, quarantineIntakeService, catalogFileRepository,
				executionProgressService, fileSystem);
	}

	@Test
	void rejectsWhatTheGuardRefuses(@TempDir Path folder, @TempDir Path quarantine) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		ExplorerDeletionService service = service();

		when(guard.refusal(any())).thenReturn(Optional.of(ExplorerMessages.pathGone()));

		service.quarantine(command(file, quarantine), ownership);
		service.deletePermanently(command(file, null), ownership);

		Assertions.assertThat(file).exists();

		verify(executionProgressService, times(2)).reject(any(), carrying("backend.files.pathGone"));
	}

	/**
	 * Moving a file the catalog never saw would put it somewhere no restore can
	 * find it, so it stays where it is and the message explains why.
	 */
	@Test
	void rejectsQuarantineWhenNothingUnderThePathIsCataloged(@TempDir Path folder, @TempDir Path quarantine)
			throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());

		service().quarantine(command(file, quarantine), ownership);

		Assertions.assertThat(file).exists();

		verify(quarantineIntakeService, never()).intake(any(), any(), any(), any(), any());
		verify(executionProgressService).reject(any(), carrying("backend.files.quarantineNothingCataloged"));
	}

	@Test
	void sendsACatalogedFileThroughTheSameIntakeTheDuplicateScreenUses(@TempDir Path folder, @TempDir Path quarantine)
			throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		CatalogFile stored = CatalogFile.builder().fileKey(PathUtils.normalize(file)).build();

		when(catalogFileRepository.findByFileKey(PathUtils.normalize(file))).thenReturn(Optional.of(stored));
		when(quarantineIntakeService.intake(any(), any(), any(), any(), any())).thenReturn(IntakeOutcome.MOVED);

		service().quarantine(command(file, quarantine), ownership);

		verify(quarantineIntakeService).intake(any(Execution.class), any(CatalogFile.class), eq(quarantine), any(),
				eq(EXECUTION_ID));
		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.FINISHED),
				eq(new ExecutionCounts(1, 1, 0, 0)), carrying("backend.files.quarantineDone", 1, 0, 0));
	}

	@Test
	void deletesAFileFromDiskAndMarksTheCatalogEntry(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		CatalogFile stored = CatalogFile.builder().fileKey(PathUtils.normalize(file)).build();

		when(catalogFileRepository.findByFileKey(PathUtils.normalize(file))).thenReturn(Optional.of(stored));

		service().deletePermanently(command(file, null), ownership);

		Assertions.assertThat(file).doesNotExist();
		Assertions.assertThat(stored.getLifecycleStatus()).isEqualTo(LifecycleStatus.DELETED);

		verify(catalogFileRepository).saveAll(List.of(stored));
		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.FINISHED),
				eq(new ExecutionCounts(1, 1, 0, 0)), carrying("backend.files.deleteDone", 1));
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

		service().deletePermanently(command(folder, null), ownership);

		Assertions.assertThat(folder).doesNotExist();

		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.FINISHED),
				eq(new ExecutionCounts(2, 2, 0, 0)), carrying("backend.files.deleteDone", 2));
	}

	/**
	 * Nothing may be destroyed after the locks have gone, so possession is
	 * confirmed at the last moment where stopping still costs nothing.
	 */
	@Test
	void confirmsItStillOwnsTheTreeBeforeTheFirstRemoval(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());

		service().deletePermanently(command(file, null), ownership);

		verify(ownership).assertStillOwned();
	}

	/**
	 * A folder is quarantined file by file, and once the last one has gone the
	 * empty container is removed too - leaving it behind would show an empty folder
	 * that the user believes they deleted.
	 */
	@Test
	void quarantinesEveryCatalogedFileUnderAFolderAndRemovesTheEmptyFolder(@TempDir Path parent,
			@TempDir Path quarantine) throws IOException {
		Path folder = Files.createDirectory(parent.resolve("album"));
		Path first = Files.createFile(folder.resolve("a.jpg"));
		Path second = Files.createFile(folder.resolve("b.jpg"));

		when(catalogFileRepository.findByFileKey(PathUtils.normalize(first)))
				.thenReturn(Optional.of(CatalogFile.builder().fileKey(PathUtils.normalize(first)).build()));
		when(catalogFileRepository.findByFileKey(PathUtils.normalize(second)))
				.thenReturn(Optional.of(CatalogFile.builder().fileKey(PathUtils.normalize(second)).build()));
		when(quarantineIntakeService.intake(any(), any(), any(), any(), any())).thenAnswer(movingTheFileOut());

		service().quarantine(command(folder, quarantine), ownership);

		Assertions.assertThat(folder).doesNotExist();

		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.FINISHED),
				eq(new ExecutionCounts(2, 2, 0, 0)), carrying("backend.files.quarantineDone", 2, 0, 0));
	}

	/**
	 * A file the intake could not move is counted as a failure, and the run is
	 * reported as finished with errors rather than as a clean sweep.
	 */
	@Test
	void reportsAnIntakeFailureInsteadOfClaimingSuccess(@TempDir Path folder, @TempDir Path quarantine)
			throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		when(catalogFileRepository.findByFileKey(PathUtils.normalize(file)))
				.thenReturn(Optional.of(CatalogFile.builder().fileKey(PathUtils.normalize(file)).build()));
		when(quarantineIntakeService.intake(any(), any(), any(), any(), any())).thenReturn(IntakeOutcome.ERROR);

		service().quarantine(command(file, quarantine), ownership);

		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.FINISHED_WITH_ERRORS),
				eq(new ExecutionCounts(1, 0, 0, 1)), carrying("backend.files.quarantineDone", 0, 0, 1));
	}

	/**
	 * A file the intake decided not to take - already removed, already under
	 * quarantine, no longer on disk - is neither moved nor failed. It is counted as
	 * kept, which is also what keeps the folder from being reported as emptied.
	 */
	@Test
	void countsAFileTheIntakeSkippedAsKeptRatherThanMoved(@TempDir Path parent, @TempDir Path quarantine)
			throws IOException {
		Path folder = Files.createDirectory(parent.resolve("album"));
		Path taken = Files.createFile(folder.resolve("a.jpg"));
		Path left = Files.createFile(folder.resolve("b.jpg"));

		when(catalogFileRepository.findByFileKey(PathUtils.normalize(taken)))
				.thenReturn(Optional.of(CatalogFile.builder().fileKey(PathUtils.normalize(taken)).build()));
		when(catalogFileRepository.findByFileKey(PathUtils.normalize(left)))
				.thenReturn(Optional.of(CatalogFile.builder().fileKey(PathUtils.normalize(left)).build()));
		when(quarantineIntakeService.intake(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
			CatalogFile file = invocation.getArgument(1);

			if (file.getFileKey().equals(PathUtils.normalize(left))) {
				return IntakeOutcome.SKIPPED;
			}

			Files.deleteIfExists(Path.of(file.getFileKey()));

			return IntakeOutcome.MOVED;
		});

		service().quarantine(command(folder, quarantine), ownership);

		Assertions.assertThat(folder).exists();

		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.FINISHED),
				eq(new ExecutionCounts(2, 1, 1, 0)), carrying("backend.files.quarantineDone", 1, 1, 0));
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

		when(refusing.deleteRecursively(any(), any())).thenThrow(new IOException("in use"));
		when(catalogFileRepository.findByFileKey(any()))
				.thenReturn(Optional.of(CatalogFile.builder().fileKey(PathUtils.normalize(file)).build()));

		service(refusing).deletePermanently(command(file, null), ownership);

		verify(catalogFileRepository, never()).saveAll(any());
		verify(executionProgressService).fail(any(), carrying("backend.files.deleteFailed", "in use"));
	}

	/**
	 * A folder that cannot be listed yields no candidates, so the run refuses
	 * instead of reporting that it quarantined nothing successfully.
	 */
	@Test
	void rejectsQuarantineWhenTheFolderCannotBeListed(@TempDir Path folder, @TempDir Path quarantine)
			throws IOException {
		ExplorerFileSystem refusing = mock(ExplorerFileSystem.class);

		when(refusing.isDirectory(any())).thenReturn(true);
		when(refusing.listFiles(any())).thenThrow(new IOException("permission denied"));

		service(refusing).quarantine(command(folder, quarantine), ownership);

		verify(executionProgressService).reject(any(), carrying("backend.files.quarantineNothingCataloged"));
	}

	/**
	 * Failing to remove the emptied folder is not worth failing the quarantine
	 * over: every file already moved, and the leftover container is reported in the
	 * log rather than turned into an error the user cannot act on.
	 */
	@Test
	void keepsTheQuarantineSuccessfulWhenTheEmptiedFolderCannotBeRemoved(@TempDir Path folder,
			@TempDir Path quarantine) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		ExplorerFileSystem refusing = mock(ExplorerFileSystem.class);

		when(refusing.isDirectory(folder)).thenReturn(true);
		when(refusing.listFiles(any())).thenReturn(List.of(file));
		doThrow(new IOException("not empty")).when(refusing).deleteEmptyTree(any(), any());
		when(catalogFileRepository.findByFileKey(PathUtils.normalize(file)))
				.thenReturn(Optional.of(CatalogFile.builder().fileKey(PathUtils.normalize(file)).build()));
		when(quarantineIntakeService.intake(any(), any(), any(), any(), any())).thenReturn(IntakeOutcome.MOVED);

		service(refusing).quarantine(command(folder, quarantine), ownership);

		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.FINISHED),
				eq(new ExecutionCounts(1, 1, 0, 0)), carrying("backend.files.quarantineDoneFolderKept", 1));
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

		when(catalogFileRepository.findByFileKey(PathUtils.normalize(photo)))
				.thenReturn(Optional.of(CatalogFile.builder().fileKey(PathUtils.normalize(photo)).build()));
		when(quarantineIntakeService.intake(any(), any(), any(), any(), any())).thenAnswer(movingTheFileOut());

		service().quarantine(command(album, quarantine), ownership);

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

		when(catalogFileRepository.findByFileKey(any())).thenAnswer(invocation -> {
			String key = invocation.getArgument(0);

			return key.equals(PathUtils.normalize(known)) ? Optional.of(CatalogFile.builder().fileKey(key).build())
					: Optional.empty();
		});
		when(quarantineIntakeService.intake(any(), any(), any(), any(), any())).thenAnswer(movingTheFileOut());

		service().quarantine(command(album, quarantine), ownership);

		Assertions.assertThat(album).exists();
		Assertions.assertThat(album.resolve("unknown.jpg")).exists();
	}

	/**
	 * An empty folder is what a previous quarantine leaves behind, and refusing to
	 * remove it would strand the user with a folder they cannot delete from the
	 * very screen that emptied it. There is nothing to protect, so it just goes.
	 */
	@Test
	void removesAnEmptyFolderInsteadOfRefusingIt(@TempDir Path parent, @TempDir Path quarantine) throws IOException {
		Path album = Files.createDirectory(parent.resolve("album"));

		Files.createDirectory(album.resolve("2008"));

		service().quarantine(command(album, quarantine), ownership);

		Assertions.assertThat(album).doesNotExist();

		verify(quarantineIntakeService, never()).intake(any(), any(), any(), any(), any());
		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.FINISHED), eq(ExecutionCounts.one()),
				carrying("backend.files.emptyFolderRemoved"));
	}

	/**
	 * The folder could not be removed, so the row must not claim it was. Saying
	 * "empty folder removed" while it sits on screen sends the user back to try the
	 * same thing again, which is exactly what happened with a read-only folder
	 * synced from a phone.
	 */
	@Test
	void rejectsWhenTheEmptyFolderCannotBeRemoved(@TempDir Path parent, @TempDir Path quarantine) throws IOException {
		Path album = Files.createDirectory(parent.resolve("album"));

		ExplorerFileSystem refusing = mock(ExplorerFileSystem.class);

		when(refusing.isDirectory(any())).thenReturn(true);
		when(refusing.listFiles(any())).thenReturn(List.of());
		doThrow(new IOException("access denied")).when(refusing).deleteEmptyTree(any(), any());

		service(refusing).quarantine(command(album, quarantine), ownership);

		verify(executionProgressService).reject(any(), carrying("backend.files.folderNotRemoved"));
	}

	/**
	 * The real intake moves the file out; mirroring that here is what lets the
	 * service find the folder empty afterwards.
	 */
	private Answer<IntakeOutcome> movingTheFileOut() {
		return invocation -> {
			Files.deleteIfExists(Path.of(((CatalogFile) invocation.getArgument(1)).getFileKey()));

			return IntakeOutcome.MOVED;
		};
	}

	private Execution command(Path target, Path quarantineRoot) {
		return Execution.builder().id(EXECUTION_ID).executionType(ExecutionType.EXPLORER_QUARANTINE)
				.sourcePath(PathUtils.normalize(target))
				.targetPath(quarantineRoot == null ? null : PathUtils.normalize(quarantineRoot)).build();
	}

	/**
	 * The real port over a real registry: what is being tested is a deletion that
	 * actually happens on disk and is actually announced, so a mock here would
	 * assert that a method was called rather than that a file went away.
	 */
	private static SecureLibraryFiles libraryFiles() {
		SelfWrittenPathRegistry registry = new SelfWrittenPathRegistry(new InMemorySelfWrittenPaths(),
				Clock.systemUTC());

		return new SecureLibraryFiles(
				new SecureFileMove(new OrganizationMoveVerifier(new FileHashService()), registry), registry);
	}
}