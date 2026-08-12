package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import static br.com.jorgemelo.nimbusfilemanager.media.application.explorer.CarriedMessages.carrying;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.EligibilityAnnouncer;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.media.application.constants.ExplorerMessages;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MoveBaseline;
import br.com.jorgemelo.nimbusfilemanager.shared.PreparedMovements;
import br.com.jorgemelo.nimbusfilemanager.shared.application.catalog.CatalogConvergenceMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PreparedMovement;
import br.com.jorgemelo.nimbusfilemanager.shared.application.library.LibraryFileMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
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
	/** The run that ordered the rename, which is not what any fact is named after. */
	private static final UUID EXECUTION_PUBLIC_ID = UUID.fromString("0199c0de-0000-7000-8000-000000000042");

	private final ExplorerDeletionGuard guard = mock(ExplorerDeletionGuard.class);
	private final LibraryFileMutations libraryFileMutations = mock(LibraryFileMutations.class);
	private final ExplorerRenamePersistence explorerRenamePersistence = mock(ExplorerRenamePersistence.class);
	private final CatalogConvergenceMutations catalogMutations = mock(CatalogConvergenceMutations.class);
	private final ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);
	private final ExecutionOwnership ownership = mock(ExecutionOwnership.class);
	private final EligibilityAnnouncer eligibilityAnnouncer = mock(EligibilityAnnouncer.class);
	private final ExplorerRelocationPlan explorerRelocationPlan = mock(ExplorerRelocationPlan.class);

	private ExplorerRenameService service() {
		return new ExplorerRenameService(guard, libraryFileMutations, explorerRenamePersistence,
				explorerRelocationPlan, catalogMutations, executionProgressService, eligibilityAnnouncer,
				Clock.systemUTC());
	}

	@Test
	void movesTheFileUnderItsExecutionAndRepointsTheCatalog(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));
		Path renamed = folder.resolve("holiday.jpg");

		PreparedMovement prepared = PreparedMovements.pending(1L, 7L, file, renamed);

		MoveBaseline proven = new MoveBaseline(1024L, "digest-proved-by-the-move");

		when(guard.refusal(any())).thenReturn(Optional.empty());
		when(explorerRelocationPlan.reserve(any(), eq(file), eq(renamed), eq(false))).thenReturn(List.of(prepared));
		when(libraryFileMutations.move(file, renamed, false, EXECUTION_ID)).thenReturn(proven);

		service().execute(rename(file, renamed), ownership);

		verify(libraryFileMutations).move(file, renamed, false, EXECUTION_ID);
		verify(explorerRenamePersistence).rename(file, renamed, prepared.catalogFileEventPublicId(), proven);
		verify(explorerRelocationPlan).settle(any(), eq(List.of(prepared)));
		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.FINISHED), eq(ExecutionCounts.one()),
				carrying("backend.files.renameDone", "holiday.jpg"));
	}

	/**
	 * One file renamed is one operation and one fact, and the fact is not named
	 * after the run. A run may order many operations - the folder rename below
	 * orders one per file - so an identity taken from the execution would be one
	 * identity for many facts, and every fact after the first would be refused.
	 */
	@Test
	void namesTheFactAfterTheOperationAndNeverAfterTheExecution(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));
		Path renamed = folder.resolve("holiday.jpg");

		PreparedMovement prepared = PreparedMovements.pending(1L, 7L, file, renamed);

		when(guard.refusal(any())).thenReturn(Optional.empty());
		when(explorerRelocationPlan.reserve(any(), eq(file), eq(renamed), eq(false))).thenReturn(List.of(prepared));

		service().execute(rename(file, renamed), ownership);

		Assertions.assertThat(prepared.catalogFileEventPublicId()).isNotEqualTo(EXECUTION_PUBLIC_ID)
				.isNotEqualTo(prepared.movementPublicId());

		verify(explorerRenamePersistence).rename(eq(file), eq(renamed), eq(prepared.catalogFileEventPublicId()),
				any());
	}

	/**
	 * A file the catalog has never known is renamed on disk and nothing is written
	 * about it. There is no entry to correct, and inventing one would be inventing
	 * history for a file the product has no opinion on.
	 */
	@Test
	void renamingAFileNobodyCataloguedWritesNoFact(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));
		Path renamed = folder.resolve("holiday.jpg");

		when(guard.refusal(any())).thenReturn(Optional.empty());
		when(explorerRelocationPlan.reserve(any(), eq(file), eq(renamed), eq(false))).thenReturn(List.of());

		service().execute(rename(file, renamed), ownership);

		verify(libraryFileMutations).move(file, renamed, false, EXECUTION_ID);
		verify(explorerRenamePersistence, never()).rename(any(), any(), any(), any());
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
				anyList(), anyList(), argThat(provenance -> CatalogEventSources.EXPLORER.equals(provenance.source())));
	}

	/**
	 * The shape of a folder rename, which is the whole reason the identities are
	 * reserved before anything moves: one run, one operation per catalogued file
	 * under it, and one fact per operation - all decided while walking away is
	 * still free. An execution cannot supply that on its own, having exactly one
	 * identity for however many files the folder holds.
	 */
	@Test
	void aFolderRenameIsOneRunWithOneOperationAndOneFactPerCataloguedFile(@TempDir Path parent) throws IOException {
		Path folder = Files.createDirectory(parent.resolve("album"));
		Path renamed = parent.resolve("viagem");

		PreparedMovement first = PreparedMovements.pending(1L, 11L, folder.resolve("a.jpg"), renamed.resolve("a.jpg"));
		PreparedMovement second = PreparedMovements.pending(2L, 22L, folder.resolve("b.jpg"), renamed.resolve("b.jpg"));

		when(guard.refusal(any())).thenReturn(Optional.empty());
		when(explorerRelocationPlan.reserve(any(), eq(folder), eq(renamed), eq(true)))
				.thenReturn(List.of(first, second));

		service().execute(rename(folder, renamed), ownership);

		Assertions.assertThat(List.of(first.catalogFileEventPublicId(), second.catalogFileEventPublicId()))
				.as("a fact each, and neither of them the run that ordered both")
				.doesNotHaveDuplicates().doesNotContain(EXECUTION_PUBLIC_ID);

		verify(catalogMutations).repointFolder(eq(PathUtils.normalize(folder)), eq(PathUtils.normalize(renamed)),
				eq(List.of(11L, 22L)),
				eq(List.of(first.catalogFileEventPublicId(), second.catalogFileEventPublicId())), any());

		verify(explorerRelocationPlan).settle(any(), eq(List.of(first, second)));
	}

	/**
	 * Renaming one file leaves the folder it is in alone, and the folder is the
	 * only thing about a placement a duplicate analysis reads. So there is nothing
	 * to bring up to date, whatever the file ends up being called.
	 */
	@Test
	void renamingOneFileAsksForNoRegroup(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		when(guard.refusal(any())).thenReturn(Optional.empty());

		service().execute(rename(file, folder.resolve("holiday.jpg")), ownership);

		verifyNoInteractions(eligibilityAnnouncer);
	}

	/**
	 * A folder rename does move every file under it, and whether that matters is a
	 * question about the exclusion list - asked once for the operation, not once
	 * per file.
	 */
	@Test
	void renamingAFolderAcrossAnExcludedOneAsksForOneRegroup(@TempDir Path parent) throws IOException {
		Path folder = Files.createDirectory(parent.resolve("album"));
		Path renamed = parent.resolve("viagem");

		when(guard.refusal(any())).thenReturn(Optional.empty());
		when(catalogMutations.repointFolder(any(), any(), anyList(), anyList(), any())).thenReturn(12);
		when(eligibilityAnnouncer.repointCanChangeEligibility(PathUtils.normalize(folder),
				PathUtils.normalize(renamed))).thenReturn(true);

		doAnswer(invocation -> {
			Files.move(invocation.getArgument(0), invocation.getArgument(1));

			return null;
		}).when(libraryFileMutations).renameDirectory(any(), any(), any());

		service().execute(rename(folder, renamed), ownership);

		verify(eligibilityAnnouncer).announce("explorer folder rename");
	}

	/**
	 * The same rename with nothing hidden anywhere near it: a hundred thousand
	 * files change folder and not one of them changes whether it may be compared.
	 */
	@Test
	void renamingAFolderNoExclusionCaresAboutAsksForNothing(@TempDir Path parent) throws IOException {
		Path folder = Files.createDirectory(parent.resolve("album"));
		Path renamed = parent.resolve("viagem");

		when(guard.refusal(any())).thenReturn(Optional.empty());
		when(catalogMutations.repointFolder(any(), any(), anyList(), anyList(), any())).thenReturn(12);

		doAnswer(invocation -> {
			Files.move(invocation.getArgument(0), invocation.getArgument(1));

			return null;
		}).when(libraryFileMutations).renameDirectory(any(), any(), any());

		service().execute(rename(folder, renamed), ownership);

		verify(eligibilityAnnouncer, never()).announce(any());
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
		Path renamed = folder.resolve("holiday.jpg");

		PreparedMovement prepared = PreparedMovements.pending(1L, 7L, file, renamed);

		when(guard.refusal(any())).thenReturn(Optional.empty());
		when(explorerRelocationPlan.reserve(any(), eq(file), eq(renamed), eq(false))).thenReturn(List.of(prepared));
		doThrow(new IOException("disk full")).when(libraryFileMutations).move(any(), any(), anyBoolean(), any());

		service().execute(rename(file, renamed), ownership);

		verify(explorerRenamePersistence, never()).rename(any(), any(), any(), any());
		verify(executionProgressService).fail(any(), carrying("backend.files.renameFailed", "disk full"));

		// The operation is closed as failed rather than left pending, which is what
		// would let a later reader believe the file had moved.
		verify(explorerRelocationPlan).abandon(any(), eq(List.of(prepared)), eq(MovementReason.IO_ERROR));
		verify(explorerRelocationPlan, never()).settle(any(), any());
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

		verify(ownership).assertMayGoOnWorking();
	}

	private Execution rename(Path source, Path target) {
		return Execution.builder().id(EXECUTION_ID).executionPublicId(EXECUTION_PUBLIC_ID)
				.executionType(ExecutionType.EXPLORER_RENAME)
				.sourcePath(PathUtils.normalize(source)).targetPath(PathUtils.normalize(target)).build();
	}
}