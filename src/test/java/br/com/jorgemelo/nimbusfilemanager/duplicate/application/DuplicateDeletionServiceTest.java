package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateDeletionResult;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.execution.application.NoCancellations;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLock;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OwnershipLostException;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.MoveIntegrityException;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationMoveVerifier;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureFileMove;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureLibraryFiles;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MoveBaseline;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantineIntakeService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.QuarantineFolderPolicy;
import br.com.jorgemelo.nimbusfilemanager.shared.application.InMemorySelfWrittenPaths;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

class DuplicateDeletionServiceTest {

	private final SelfWrittenPathRegistry pathRegistry = new SelfWrittenPathRegistry(new InMemorySelfWrittenPaths(),
			Clock.systemDefaultZone());
	private final CatalogFileRepository catalogFileRepository = mock(CatalogFileRepository.class);
	private final QuarantineFolderPolicy quarantineFolderPolicy = mock(QuarantineFolderPolicy.class);
	private final DuplicateDeletionPersistence persistence = mock(DuplicateDeletionPersistence.class);
	private final OperationLockService operationLockService = mock(OperationLockService.class);
	private final OperationLock operationLock = mock(OperationLock.class);
	private final QuarantineIntakeService quarantineIntakeService = new QuarantineIntakeService(persistence,
			new SecureLibraryFiles(
					new SecureFileMove(new OrganizationMoveVerifier(new FileHashService()), pathRegistry),
					pathRegistry), quarantineFolderPolicy);
	private final ExecutionErrorService executionErrorService = mock(ExecutionErrorService.class);
	private final ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);

	/** The row a worker claimed, handed over rather than opened here. */
	private final Execution execution = mock(Execution.class);
	private final ExecutionCancellationService executionCancellationService = NoCancellations.none();
	private final EligibilityAnnouncer eligibilityAnnouncer = mock(EligibilityAnnouncer.class);

	private final ExecutionOwnership ownership = Takings.owning(1L);

	private final DuplicateDeletionService service = new DuplicateDeletionService(catalogFileRepository,
			quarantineIntakeService, operationLockService, executionProgressService, executionCancellationService,
			executionErrorService, eligibilityAnnouncer);

	DuplicateDeletionServiceTest() {
		when(operationLockService.acquire(eq(ExecutionType.DEDUP_DELETE), any(Path[].class))).thenReturn(operationLock);
		when(execution.getId()).thenReturn(1L);
	}

	@Test
	void refusesWhenQuarantineFolderIsNotConfigured() {
		when(quarantineFolderPolicy.root()).thenReturn(Optional.empty());

		DuplicateDeletionResult result = delete(service, List.of(UUID.randomUUID()));

		Assertions.assertThat(result.configured()).isFalse();

		verify(catalogFileRepository, never()).findByPublicIdIn(any());
	}

	/**
	 * Every file that moved left the set a duplicate analysis looks at, and the
	 * batch says so once - not once per file. Three files would otherwise be three
	 * requests for work whose answer is identical, and the third would be about a
	 * library the first two already described.
	 */
	@Test
	void aBatchAsksForOneRegroupHoweverManyFilesItMoved(@TempDir Path tmp) throws Exception {
		Path library = Files.createDirectories(tmp.resolve("library"));

		configureTrash(tmp.resolve("trash"));

		stubExecution();

		CatalogFile first = stubFile(10L, Files.writeString(library.resolve("a.jpg"), "a"), "a.jpg");
		CatalogFile second = stubFile(11L, Files.writeString(library.resolve("b.jpg"), "b"), "b.jpg");
		CatalogFile third = stubFile(12L, Files.writeString(library.resolve("c.jpg"), "c"), "c.jpg");

		when(catalogFileRepository.findByPublicIdIn(any())).thenReturn(List.of(first, second, third));

		DuplicateDeletionResult result = delete(service,
				List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));

		Assertions.assertThat(result.moved()).isEqualTo(3);

		verify(eligibilityAnnouncer).announce("duplicate removal");
	}

	/**
	 * The selection resolved to nothing, so nothing left the analysed set and there
	 * is nothing to bring up to date.
	 */
	@Test
	void aBatchThatMovedNothingAsksForNothing(@TempDir Path tmp) {
		configureTrash(tmp.resolve("trash"));

		stubExecution();

		when(catalogFileRepository.findByPublicIdIn(any())).thenReturn(List.of());

		DuplicateDeletionResult result = delete(service, List.of(UUID.randomUUID()));

		Assertions.assertThat(result.moved()).isZero();

		verifyNoInteractions(eligibilityAnnouncer);
	}

	@Test
	void movesSelectedFilesToQuarantineAndUpdatesTheCatalog(@TempDir Path tmp) throws Exception {
		Path library = Files.createDirectories(tmp.resolve("library"));
		Path trash = tmp.resolve("trash");
		Path original = Files.writeString(library.resolve("a.jpg"), "content");

		configureTrash(trash);

		stubExecution();

		CatalogFile file = stubFile(10L, original, "a.jpg");

		when(catalogFileRepository.findByPublicIdIn(any())).thenReturn(List.of(file));

		DuplicateDeletionResult result = delete(service, List.of(UUID.randomUUID()));

		Path quarantined = trash.resolve("exec-1").resolve("10__a.jpg");

		Assertions.assertThat(result.configured()).isTrue();
		Assertions.assertThat(result.moved()).isEqualTo(1);
		Assertions.assertThat(result.errors()).isZero();
		Assertions.assertThat(Files.exists(original)).isFalse();
		Assertions.assertThat(Files.exists(quarantined)).isTrue();

		ArgumentCaptor<Path[]> lockedPaths = ArgumentCaptor.forClass(Path[].class);

		verify(operationLockService).acquire(eq(ExecutionType.DEDUP_DELETE), lockedPaths.capture());

		Assertions.assertThat(lockedPaths.getValue()).containsExactlyInAnyOrder(trash.toAbsolutePath().normalize(),
				original);

		verify(operationLock).close();
		verify(persistence).persistQuarantine(any(), any(), any(), any(), any());
	}

	@Test
	void reportsNothingSelectedWhenNoIdsAreGiven() {
		when(quarantineFolderPolicy.root()).thenReturn(Optional.of(Path.of("/tmp/trash").toAbsolutePath().normalize()));

		DuplicateDeletionResult result = delete(service, List.of());

		Assertions.assertThat(result.configured()).isTrue();
		Assertions.assertThat(result.moved()).isZero();

	}

	@Test
	void rollsTheFileBackWhenTheCatalogUpdateFails(@TempDir Path tmp) throws Exception {
		Path library = Files.createDirectories(tmp.resolve("library"));
		Path trash = tmp.resolve("trash");
		Path original = Files.writeString(library.resolve("b.jpg"), "data");

		configureTrash(trash);

		stubExecution();

		CatalogFile file = stubFile(12L, original, "b.jpg");

		when(catalogFileRepository.findByPublicIdIn(any())).thenReturn(List.of(file));
		doThrow(new IllegalStateException("db down")).when(persistence).persistQuarantine(any(), any(), any(), any(),
				any());

		DuplicateDeletionResult result = delete(service, List.of(UUID.randomUUID()));

		Assertions.assertThat(result.moved()).isZero();
		Assertions.assertThat(result.errors()).isEqualTo(1);
		Assertions.assertThat(Files.exists(original)).isTrue();

		// Counted and named: the execution screen used to show the number alone, so a
		// deletion that failed gave no way to tell which file it was.
		verify(executionErrorService).save(eq(original), eq(ExecutionErrorType.MOVE_ERROR), any(), any());
	}

	@Test
	void rollsTheFileBackToItsOriginWhenTheIntegrityVerifyFails(@TempDir Path tmp) throws Exception {
		Path library = Files.createDirectories(tmp.resolve("library"));
		Path trash = tmp.resolve("trash");
		Path original = Files.writeString(library.resolve("c.jpg"), "payload");

		// A secure move that physically relocates the file but then fails the SHA-256
		// verify - exactly an on-disk corruption detected mid-move. The service must
		// put
		// the file back at its origin so nothing is left half-moved in quarantine.
		OrganizationMoveVerifier verifier = mock(OrganizationMoveVerifier.class);

		when(verifier.capture(any())).thenReturn(new MoveBaseline(7L, "sha"));
		doThrow(new MoveIntegrityException("sha mismatch")).when(verifier).verify(any(), any(), any());

		DuplicateDeletionService integrityFailingService = new DuplicateDeletionService(catalogFileRepository,
				new QuarantineIntakeService(persistence,
						new SecureLibraryFiles(new SecureFileMove(verifier, pathRegistry), pathRegistry),
						quarantineFolderPolicy),
				operationLockService, executionProgressService, executionCancellationService, executionErrorService,
				eligibilityAnnouncer);

		configureTrash(trash);

		stubExecution();

		CatalogFile file = stubFile(13L, original, "c.jpg");

		when(catalogFileRepository.findByPublicIdIn(any())).thenReturn(List.of(file));

		DuplicateDeletionResult result = delete(integrityFailingService, List.of(UUID.randomUUID()));

		Assertions.assertThat(result.moved()).isZero();
		Assertions.assertThat(result.errors()).isEqualTo(1);
		// Rollback restored the file to its original location; quarantine stays empty.
		Assertions.assertThat(original).hasContent("payload");
		Assertions.assertThat(trash.resolve("exec-1").resolve("13__c.jpg")).doesNotExist();

		verify(persistence, never()).persistQuarantine(any(), any(), any(), any(), any());
	}

	@Test
	void keepsTheFileOrphanedInQuarantineWhenBothCatalogUpdateAndRollbackFail(@TempDir Path tmp) throws Exception {
		Path library = Files.createDirectories(tmp.resolve("library"));
		Path trash = tmp.resolve("trash");
		Path original = Files.writeString(library.resolve("d.jpg"), "payload");

		configureTrash(trash);

		stubExecution();

		CatalogFile file = stubFile(14L, original, "d.jpg");

		when(catalogFileRepository.findByPublicIdIn(any())).thenReturn(List.of(file));
		// The catalog update fails AND re-creates the original path, so the physical
		// roll-back (which never overwrites) cannot move the file back.
		doAnswer(_ -> {
			Files.writeString(original, "blocker");

			throw new IllegalStateException("db down");
		}).when(persistence).persistQuarantine(any(), any(), any(), any(), any());

		DuplicateDeletionResult result = delete(service, List.of(UUID.randomUUID()));

		Path quarantined = trash.resolve("exec-1").resolve("14__d.jpg");

		Assertions.assertThat(result.errors()).isEqualTo(1);
		Assertions.assertThat(result.moved()).isZero();
		// Roll-back could not restore it, so the file stays orphaned at the quarantine
		// target.
		Assertions.assertThat(Files.exists(quarantined)).isTrue();
		Assertions.assertThat(original).hasContent("blocker");
	}

	@Test
	void countsSelectedIdsWithNoCatalogEntryAsSkippedSoTotalsAddUp(@TempDir Path tmp) throws Exception {
		Path library = Files.createDirectories(tmp.resolve("library"));
		Path trash = tmp.resolve("trash");
		Path original = Files.writeString(library.resolve("e.jpg"), "payload");

		configureTrash(trash);

		stubExecution();

		CatalogFile file = stubFile(15L, original, "e.jpg");

		// Two ids requested, but only one resolves to an active catalog entry.
		when(catalogFileRepository.findByPublicIdIn(any())).thenReturn(List.of(file));

		DuplicateDeletionResult result = delete(service, List.of(UUID.randomUUID(), UUID.randomUUID()));

		Assertions.assertThat(result.requested()).isEqualTo(2);
		Assertions.assertThat(result.moved()).isEqualTo(1);
		Assertions.assertThat(result.skipped()).isEqualTo(1);
		Assertions.assertThat(result.errors()).isZero();
		// moved + skipped + errors must always add up to what the user requested.
		Assertions.assertThat(result.moved() + result.skipped() + result.errors()).isEqualTo(result.requested());
	}

	@Test
	void skipsFilesThatAreNoLongerOnDisk(@TempDir Path tmp) {
		Path trash = tmp.resolve("trash");
		Path missing = tmp.resolve("library").resolve("gone.jpg");

		configureTrash(trash);

		stubExecution();

		CatalogFile file = stubFile(11L, missing, "gone.jpg");

		when(catalogFileRepository.findByPublicIdIn(any())).thenReturn(List.of(file));

		DuplicateDeletionResult result = delete(service, List.of(UUID.randomUUID()));

		Assertions.assertThat(result.moved()).isZero();
		Assertions.assertThat(result.skipped()).isEqualTo(1);

		verify(persistence, never()).persistQuarantine(any(), any(), any(), any(), any());
	}

	@Test
	void skipsFilesThatWereAlreadyDeletedWithoutMovingThemAgain(@TempDir Path tmp) throws Exception {
		Path trash = Files.createDirectories(tmp.resolve("trash"));
		Path alreadyQuarantined = Files.createDirectories(trash.resolve("exec-7")).resolve("10__document.pdf");

		Files.writeString(alreadyQuarantined, "content");

		configureTrash(trash);

		stubExecution();

		CatalogFile file = stubFile(10L, alreadyQuarantined, "10__document.pdf");

		when(file.isActive()).thenReturn(false);
		when(file.getLifecycleStatus()).thenReturn(LifecycleStatus.DELETED);
		when(catalogFileRepository.findByPublicIdIn(any())).thenReturn(List.of(file));

		DuplicateDeletionResult result = delete(service, List.of(file.getPublicId()));

		Assertions.assertThat(result.moved()).isZero();
		Assertions.assertThat(result.skipped()).isEqualTo(1);
		Assertions.assertThat(Files.exists(alreadyQuarantined)).isTrue();
		Assertions.assertThat(trash.resolve("exec-1").resolve("10__10__document.pdf")).doesNotExist();

		verify(persistence, never()).persistQuarantine(any(), any(), any(), any(), any());
	}

	@Test
	void skipsActiveCatalogEntriesWhosePathIsAlreadyUnderQuarantine(@TempDir Path tmp) throws Exception {
		Path trash = Files.createDirectories(tmp.resolve("trash"));
		Path alreadyQuarantined = Files.createDirectories(trash.resolve("exec-7")).resolve("10__document.pdf");

		Files.writeString(alreadyQuarantined, "content");

		configureTrash(trash);

		stubExecution();

		CatalogFile file = stubFile(10L, alreadyQuarantined, "10__document.pdf");

		when(catalogFileRepository.findByPublicIdIn(any())).thenReturn(List.of(file));

		DuplicateDeletionResult result = delete(service, List.of(file.getPublicId()));

		Assertions.assertThat(result.moved()).isZero();
		Assertions.assertThat(result.skipped()).isEqualTo(1);
		Assertions.assertThat(Files.exists(alreadyQuarantined)).isTrue();

		verify(persistence, never()).persistQuarantine(any(), any(), any(), any(), any());
	}

	@Test
	void reportsProgressForEachFileUpToTheTotal(@TempDir Path tmp) throws Exception {
		Path library = Files.createDirectories(tmp.resolve("library"));
		Path trash = tmp.resolve("trash");
		Path first = Files.writeString(library.resolve("a.jpg"), "one");
		Path second = Files.writeString(library.resolve("b.jpg"), "two");

		configureTrash(trash);

		stubExecution();

		CatalogFile firstFile = stubFile(20L, first, "a.jpg");
		CatalogFile secondFile = stubFile(21L, second, "b.jpg");

		when(catalogFileRepository.findByPublicIdIn(any())).thenReturn(List.of(firstFile, secondFile));

		delete(service, List.of(UUID.randomUUID(), UUID.randomUUID()));

		// The bar reads the row now, so the total is declared once and each file
		// finished moves the counter the screen polls - one at a time. It used to be
		// given the total on every report, which drew a full bar while the second
		// file had not been looked at.
		verify(executionProgressService).updateTotal(ownership, 2);
		verify(executionProgressService).updateLiveProgress(eq(ownership), eq(1), eq(1), anyInt(), anyInt(), any());
		verify(executionProgressService).updateLiveProgress(eq(ownership), eq(2), eq(2), anyInt(), anyInt(), any());
	}

	@Test
	void refusesTheWholeBatchWhenAnyDeletionPathIsLocked(@TempDir Path tmp) throws Exception {
		Path trash = tmp.resolve("trash");
		Path original = Files.writeString(Files.createDirectories(tmp.resolve("library")).resolve("locked.jpg"),
				"content");

		UUID publicId = UUID.randomUUID();

		configureTrash(trash);

		CatalogFile file = stubFile(30L, original, "locked.jpg");

		when(file.getPublicId()).thenReturn(publicId);
		when(catalogFileRepository.findByPublicIdIn(any())).thenReturn(List.of(file));
		when(operationLockService.acquire(eq(ExecutionType.DEDUP_DELETE), any(Path[].class)))
				.thenThrow(new OperationLockException("busy"));

		DuplicateDeletionResult result = delete(service, List.of(publicId));

		Assertions.assertThat(result.moved()).isZero();
		Assertions.assertThat(result.errors()).isEqualTo(1);
		Assertions.assertThat(Files.exists(original)).isTrue();

		verify(persistence, never()).persistQuarantine(any(), any(), any(), any(), any());
	}
	private void configureTrash(Path trash) {
		when(quarantineFolderPolicy.root()).thenReturn(Optional.of(trash.toAbsolutePath().normalize()));
	}

	private Execution stubExecution() {
		lenient().when(execution.getPublicId()).thenReturn(UUID.randomUUID());

		return execution;
	}

	private CatalogFile stubFile(long id, Path currentPath, String name) {
		CatalogFile file = mock(CatalogFile.class);

		when(file.getId()).thenReturn(id);
		when(file.getPublicId()).thenReturn(UUID.randomUUID());
		when(file.getFileKey()).thenReturn(PathUtils.normalize(currentPath));
		when(file.getFileName()).thenReturn(name);
		when(file.isActive()).thenReturn(true);

		return file;
	}
	/**
	 * A deletion can run for minutes in another process, so it has to be stoppable.
	 * It stops between files: everything already in quarantine got there under the
	 * locks and verified, and the rest simply does not start.
	 */
	@Test
	void stopsBetweenFilesWhenTheDeletionIsCancelled(@TempDir Path tmp) throws Exception {
		Path quarantine = tmp.resolve("quarantine");

		Path first = Files.writeString(tmp.resolve("first.jpg"), "first");

		stubExecution();
		configureTrash(quarantine);

		CatalogFile file = stubFile(1L, first, "first.jpg");

		when(catalogFileRepository.findByPublicIdIn(any())).thenReturn(List.of(file));

		executionCancellationService.requestCancellation(1L);

		DuplicateDeletionResult result = delete(service, List.of(UUID.randomUUID()));

		Assertions.assertThat(result.moved()).isZero();
		Assertions.assertThat(Files.exists(first)).isTrue();
	}

	/**
	 * Losing the locks closes the commit: the file that has not moved yet stays
	 * where it is, and the run stops rather than taking it out of a library this
	 * process may no longer be entitled to write to.
	 */
	@Test
	void stopsBeforeTheNextFileWhenTheLocksUnderItAreGone(@TempDir Path tmp) throws Exception {
		Path quarantine = tmp.resolve("quarantine");

		Path first = Files.writeString(tmp.resolve("first.jpg"), "first");

		stubExecution();
		configureTrash(quarantine);

		CatalogFile file = stubFile(1L, first, "first.jpg");

		when(catalogFileRepository.findByPublicIdIn(any())).thenReturn(List.of(file));

		ExecutionOwnership lost = mock(ExecutionOwnership.class);

		doThrow(new OwnershipLostException("the session that held the locks is gone")).when(lost)
				.assertMayGoOnWorking();

		List<UUID> selected = List.of(UUID.randomUUID());

		Assertions.assertThatThrownBy(() -> service.delete(selected, execution, lost))
				.isInstanceOf(OwnershipLostException.class);

		Assertions.assertThat(Files.exists(first)).isTrue();
	}

	/**
	 * The deletion runs under an execution a worker claimed and the ownership of
	 * the paths it locked, so these tests hand it the same two things.
	 */
	private DuplicateDeletionResult delete(DuplicateDeletionService deletion, Collection<UUID> publicIds) {
		return deletion.delete(publicIds, execution, ownership);
	}
}