package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import static br.com.jorgemelo.nimbusfilemanager.media.application.explorer.CarriedMessages.carrying;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.media.application.constants.ExplorerMessages;
import br.com.jorgemelo.nimbusfilemanager.shared.application.catalog.CatalogMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.application.library.LibraryFileMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Renaming off the queue. The name was already judged when the command was
 * asked for; what matters here is what happens under the locks - that the file
 * still may be renamed, that the target is still free, and that the catalog is
 * left describing where the files actually are rather than where they were.
 */
class ExplorerRenameServiceTest {

	private static final long EXECUTION_ID = 42L;

	private final ExplorerDeletionGuard guard = mock(ExplorerDeletionGuard.class);
	private final LibraryFileMutations libraryFileMutations = mock(LibraryFileMutations.class);
	private final ExplorerRenamePersistence explorerRenamePersistence = mock(ExplorerRenamePersistence.class);
	private final CatalogMutations catalogMutations = mock(CatalogMutations.class);
	private final ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);
	private final ExecutionOwnership ownership = mock(ExecutionOwnership.class);

	private ExplorerRenameService service() {
		return new ExplorerRenameService(guard, libraryFileMutations, explorerRenamePersistence, catalogMutations,
				executionProgressService, Clock.systemUTC());
	}

	@Test
	void movesTheFileUnderItsExecutionAndRepointsTheCatalog(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));
		Path renamed = folder.resolve("holiday.jpg");

		when(guard.refusal(any())).thenReturn(Optional.empty());

		service().execute(rename(file, renamed), ownership);

		verify(libraryFileMutations).move(file, renamed, false, EXECUTION_ID);
		verify(explorerRenamePersistence).rename(file, renamed);
		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.FINISHED), eq(ExecutionCounts.one()),
				carrying("backend.files.renameDone", "holiday.jpg"));
	}

	/**
	 * The announcement to the watcher is named after the execution, which is what
	 * lets a folder rename - one call, a notification per file inside it - go on
	 * being recognised as this product's own work while the run holds its paths.
	 */
	@Test
	void renamesAFolderAndMovesTheCatalogueOfItsWholeSubtree(@TempDir Path parent) throws IOException {
		Path folder = Files.createDirectory(parent.resolve("album"));
		Path renamed = parent.resolve("viagem");

		when(guard.refusal(any())).thenReturn(Optional.empty());

		// The port really renames here: what is being asserted is that a folder takes
		// the directory operation and not the verified move, and that only shows if
		// the directory actually moves.
		doAnswer(invocation -> {
			Files.move(invocation.getArgument(0), invocation.getArgument(1));

			return null;
		}).when(libraryFileMutations).renameDirectory(any(), any(), any());

		service().execute(rename(folder, renamed), ownership);

		Assertions.assertThat(renamed).exists();
		Assertions.assertThat(folder).doesNotExist();

		verify(libraryFileMutations).renameDirectory(folder, renamed, EXECUTION_ID);
		verify(libraryFileMutations, never()).move(any(), any(), anyBoolean(), any());
		verify(catalogMutations).repointFolder(eq(PathUtils.normalize(folder)), eq(PathUtils.normalize(renamed)),
				any());
	}

	/**
	 * Time passes between the click and the work, and the guard is what answers
	 * whether the path may still be written to at all.
	 */
	@Test
	void rejectsWhatTheGuardNoLongerAllows(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		when(guard.refusal(any())).thenReturn(Optional.of(ExplorerMessages.pathGone()));

		service().execute(rename(file, folder.resolve("holiday.jpg")), ownership);

		verify(libraryFileMutations, never()).move(any(), any(), anyBoolean(), any());
		verify(executionProgressService).reject(any(), carrying("backend.files.pathGone"));
	}

	/**
	 * Overwriting the neighbour would destroy a file the user never selected. The
	 * name was free when the command was queued; this is the look that decides.
	 */
	@Test
	void rejectsWhenSomethingTookTheTargetNameWhileTheCommandWaited(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));
		Path taken = Files.createFile(folder.resolve("taken.jpg"));

		when(guard.refusal(any())).thenReturn(Optional.empty());

		service().execute(rename(file, taken), ownership);

		verify(libraryFileMutations, never()).move(any(), any(), anyBoolean(), any());
		verify(executionProgressService).reject(any(), carrying("backend.files.renameTargetExists", "taken.jpg"));
	}

	/**
	 * The secure move refuses when it cannot guarantee the copy; the file stays put
	 * and the row says what went wrong instead of reporting a rename that never
	 * happened.
	 */
	@Test
	void reportsAFailedMoveInsteadOfClaimingSuccess(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		when(guard.refusal(any())).thenReturn(Optional.empty());
		doThrow(new IOException("disk full")).when(libraryFileMutations).move(any(), any(), anyBoolean(), any());

		service().execute(rename(file, folder.resolve("holiday.jpg")), ownership);

		verify(explorerRenamePersistence, never()).rename(any(), any());
		verify(executionProgressService).fail(any(), carrying("backend.files.renameFailed", "disk full"));
	}

	/**
	 * The locks can go away while the work is in flight, and nothing may be written
	 * after they have - so possession is confirmed before the file moves, not after.
	 */
	@Test
	void confirmsItStillOwnsThePathsBeforeMovingAnything(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		when(guard.refusal(any())).thenReturn(Optional.empty());

		service().execute(rename(file, folder.resolve("holiday.jpg")), ownership);

		verify(ownership).assertStillOwned();
	}

	private Execution rename(Path source, Path target) {
		return Execution.builder().id(EXECUTION_ID).executionType(ExecutionType.EXPLORER_RENAME)
				.sourcePath(PathUtils.normalize(source)).targetPath(PathUtils.normalize(target)).build();
	}

}