package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLock;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

@ExtendWith(MockitoExtension.class)
class OrganizationUndoServiceTest {

	private final SelfWrittenPathRegistry pathRegistry = new SelfWrittenPathRegistry(Clock.systemDefaultZone());

	@TempDir
	Path tempDir;

	@Mock
	private ExecutionRepository executionRepository;

	@Mock
	private CatalogFileRepository catalogFileRepository;

	@Mock
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Mock
	private MovementRepository movementRepository;

	private final OperationLockService operationLockService = new OperationLockService();

	@Test
	void undoShouldMoveFileBackAndUpdateDatabase() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));
		Path source = sourceFolder.resolve("photo.jpg");
		Path target = Files.writeString(targetFolder.resolve("photo.jpg"), "content");

		CatalogFile catalogFile = catalogFile(10L, target);

		CatalogFileLocation location = location(catalogFile, target);

		Movement movement = movement(100L, catalogFile, source, target, MovementStatus.MOVED);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR)))
				.thenReturn(List.of(movement));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(10L,
				target.toAbsolutePath().normalize().toString())).thenReturn(Optional.of(location));

		var response = service().undo(1L);

		Assertions.assertThat(response.status()).isEqualTo("FINISHED");
		Assertions.assertThat(response.undone()).isEqualTo(1);
		Assertions.assertThat(response.errors()).isZero();
		Assertions.assertThat(Files.exists(source)).isTrue();
		Assertions.assertThat(Files.exists(target)).isFalse();
		Assertions.assertThat(catalogFile.getFileKey()).isEqualTo(source.toAbsolutePath().normalize().toString());
		Assertions.assertThat(location.getCurrentPath()).isEqualTo(source.toAbsolutePath().normalize().toString());
		Assertions.assertThat(movement.getStatus()).isEqualTo(MovementStatus.UNDONE);

		// The reversal is a movement of its own, in the opposite direction: two saves,
		// the mark on the original and the row the undo appends.
		ArgumentCaptor<Movement> saved = ArgumentCaptor.forClass(Movement.class);

		verify(movementRepository, times(2)).save(saved.capture());

		Movement reverse = saved.getAllValues().get(1);

		Assertions.assertThat(reverse.getReason()).isEqualTo(MovementReason.UNDONE_BY_USER);
		Assertions.assertThat(reverse.getSourcePath()).isEqualTo(movement.getTargetPath());
	}

	@Test
	void undoRecreatesTheSourceDirectoryStructureThatOrganizationRemoved() throws Exception {
		Path sourceRoot = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));
		// The organization emptied and removed source/old/2018, so it no longer exists
		// on disk.
		Path source = sourceRoot.resolve("old").resolve("2018").resolve("photo.jpg");
		Path target = Files.writeString(targetFolder.resolve("photo.jpg"), "content");

		CatalogFile catalogFile = catalogFile(10L, target);

		CatalogFileLocation location = location(catalogFile, target);

		Movement movement = movement(100L, catalogFile, source, target, MovementStatus.MOVED);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR)))
				.thenReturn(List.of(movement));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(10L,
				target.toAbsolutePath().normalize().toString())).thenReturn(Optional.of(location));

		Assertions.assertThat(Files.exists(source.getParent())).isFalse();

		var response = service().undo(1L);

		Assertions.assertThat(response.undone()).isEqualTo(1);
		// Moving the file back rebuilt the removed source folders (SecureFileMove
		// creates parents).
		Assertions.assertThat(Files.exists(source)).isTrue();
		Assertions.assertThat(Files.isDirectory(source.getParent())).isTrue();
		Assertions.assertThat(Files.isDirectory(sourceRoot.resolve("old"))).isTrue();
	}

	@Test
	void undoShouldContinueWhenOneMovementFails() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));
		Path missingSource = sourceFolder.resolve("missing.jpg");
		Path missingTarget = targetFolder.resolve("missing.jpg");
		Path okSource = sourceFolder.resolve("ok.jpg");
		Path okTarget = Files.writeString(targetFolder.resolve("ok.jpg"), "content");

		CatalogFile catalogFile = catalogFile(10L, okTarget);

		CatalogFileLocation location = location(catalogFile, okTarget);

		Movement missing = movement(100L, catalogFile(9L, missingTarget), missingSource, missingTarget,
				MovementStatus.MOVED);
		Movement ok = movement(101L, catalogFile, okSource, okTarget, MovementStatus.MOVED);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR)))
				.thenReturn(List.of(missing, ok));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(10L,
				okTarget.toAbsolutePath().normalize().toString())).thenReturn(Optional.of(location));

		var response = service().undo(1L);

		Assertions.assertThat(response.status()).isEqualTo("FINISHED_WITH_ERRORS");
		Assertions.assertThat(response.undone()).isEqualTo(1);
		Assertions.assertThat(response.errors()).isEqualTo(1);
		Assertions.assertThat(missing.getStatus()).isEqualTo(MovementStatus.UNDO_ERROR);
		Assertions.assertThat(missing.getReason()).isEqualTo(MovementReason.SOURCE_NOT_FOUND);
		Assertions.assertThat(Files.exists(okSource)).isTrue();
	}

	/**
	 * The physical restore already happened when the catalog write runs, so a
	 * movement with no media row, or one whose placement no longer matches, has to
	 * fail loudly enough to trigger the rollback rather than leave the catalog
	 * pointing at the old path.
	 */
	@Test
	void undoShouldFailTheMovementWhenTheCatalogSideCannotBeResolved() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));

		Path orphanSource = sourceFolder.resolve("orphan.jpg");
		Path orphanTarget = Files.writeString(targetFolder.resolve("orphan.jpg"), "content");

		Path unlinkedSource = sourceFolder.resolve("unlinked.jpg");
		Path unlinkedTarget = Files.writeString(targetFolder.resolve("unlinked.jpg"), "content");

		Movement withoutCatalog = Movement.builder().id(100L).execution(execution())
				.sourcePath(orphanSource.toAbsolutePath().normalize().toString())
				.targetPath(orphanTarget.toAbsolutePath().normalize().toString()).status(MovementStatus.MOVED).build();

		Movement withoutLocation = movement(101L, catalogFile(20L, unlinkedTarget), unlinkedSource, unlinkedTarget,
				MovementStatus.MOVED);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR)))
				.thenReturn(List.of(withoutCatalog, withoutLocation));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(eq(20L), any()))
				.thenReturn(Optional.empty());

		var response = service().undo(1L);

		Assertions.assertThat(response.errors()).isEqualTo(2);
		Assertions.assertThat(response.undone()).isZero();
		Assertions.assertThat(withoutCatalog.getStatus()).isEqualTo(MovementStatus.UNDO_ERROR);
		Assertions.assertThat(withoutLocation.getStatus()).isEqualTo(MovementStatus.UNDO_ERROR);
	}

	@Test
	void undoShouldSkipMovementAlreadyUndone() {
		Path source = tempDir.resolve("source/photo.jpg");
		Path target = tempDir.resolve("target/photo.jpg");

		Movement movement = movement(100L, catalogFile(10L, source), source, target, MovementStatus.UNDONE);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR)))
				.thenReturn(List.of(movement));

		var response = service().undo(1L);

		Assertions.assertThat(response.skipped()).isEqualTo(1);
		Assertions.assertThat(response.errors()).isZero();

		verify(catalogFileLocationRepository, never()).save(any());
	}

	@Test
	void undoShouldNotOverwriteOriginalPathWhenItAlreadyExists() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));
		Path source = Files.writeString(sourceFolder.resolve("photo.jpg"), "original");
		Path target = Files.writeString(targetFolder.resolve("photo.jpg"), "organized");

		Movement movement = movement(100L, catalogFile(10L, target), source, target, MovementStatus.MOVED);

		ArgumentCaptor<Movement> captor = ArgumentCaptor.forClass(Movement.class);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR)))
				.thenReturn(List.of(movement));

		var response = service().undo(1L);

		Assertions.assertThat(response.errors()).isEqualTo(1);
		Assertions.assertThat(Files.readString(source)).isEqualTo("original");
		Assertions.assertThat(Files.readString(target)).isEqualTo("organized");

		verify(movementRepository).save(captor.capture());

		Assertions.assertThat(captor.getValue().getStatus()).isEqualTo(MovementStatus.UNDO_ERROR);
		Assertions.assertThat(captor.getValue().getReason()).isEqualTo(MovementReason.TARGET_EXISTS);
	}

	@Test
	void undoShouldReportErrorWhenTargetFileIsMissing() {
		Path source = tempDir.resolve("source/photo.jpg");
		Path target = tempDir.resolve("target/photo.jpg");

		Movement movement = movement(100L, catalogFile(10L, target), source, target, MovementStatus.MOVED);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR)))
				.thenReturn(List.of(movement));

		var response = service().undo(1L);

		Assertions.assertThat(response.errors()).isEqualTo(1);
		Assertions.assertThat(response.items()).singleElement().satisfies(item -> {
			Assertions.assertThat(item.status()).isEqualTo("ERROR");
			Assertions.assertThat(item.message()).isEqualTo("Target file does not exist.");
		});
		Assertions.assertThat(movement.getStatus()).isEqualTo(MovementStatus.UNDO_ERROR);
		Assertions.assertThat(movement.getReason()).isEqualTo(MovementReason.SOURCE_NOT_FOUND);
	}

	@Test
	void undoRollsBackThePhysicalRestoreWhenPersistenceFailsKeepingDiskAndCatalogConsistent() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));
		Path source = sourceFolder.resolve("photo.jpg");
		Path target = Files.writeString(targetFolder.resolve("photo.jpg"), "content");

		CatalogFile catalogFile = catalogFile(10L, target);

		CatalogFileLocation location = location(catalogFile, target);

		Movement movement = movement(100L, catalogFile, source, target, MovementStatus.MOVED);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR)))
				.thenReturn(List.of(movement));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(10L,
				target.toAbsolutePath().normalize().toString())).thenReturn(Optional.of(location));
		doThrow(new IllegalStateException("database down")).when(catalogFileRepository)
				.save(catalogFile);

		var response = service().undo(1L);

		Assertions.assertThat(response.status()).isEqualTo("FINISHED_WITH_ERRORS");
		Assertions.assertThat(response.errors()).isEqualTo(1);
		// The DB update failed, so the physical restore is rolled back: the file
		// returns to the
		// target and the catalog (still pointing at target) stays consistent with disk.
		Assertions.assertThat(Files.exists(source)).isFalse();
		Assertions.assertThat(Files.exists(target)).isTrue();
		Assertions.assertThat(movement.getStatus()).isEqualTo(MovementStatus.UNDO_ERROR);
		Assertions.assertThat(movement.getReason()).isEqualTo(MovementReason.IO_ERROR);

		// The reason an undo failed lives with every other per-file failure now.
		ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);

		verify(executionErrorService).save(any(), eq(ExecutionErrorType.MOVE_ERROR), message.capture(), any());

		Assertions.assertThat(message.getValue()).contains("database down");
	}

	private final ExecutionErrorService executionErrorService = mock(ExecutionErrorService.class);

	/**
	 * The undo opens an execution of its own now, so the repository has to hand one
	 * back: without it the run has nothing to finish and nothing to attach a
	 * failure to. Lenient because the tests that refuse before starting - unknown
	 * execution, execution of a type that cannot be undone - never get that far.
	 */
	private void stubUndoExecution() {
		lenient().when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
	}

	private OrganizationUndoService service() {
		stubUndoExecution();

		WorkspaceManager workspace = mock(WorkspaceManager.class);

		when(workspace.getWorkspacePath()).thenReturn(tempDir);

		return new OrganizationUndoService(executionRepository, catalogFileRepository, catalogFileLocationRepository,
				new OrganizationMovementLog(movementRepository, catalogFileRepository, executionErrorService),
				operationLockService,
				new OrganizationPathValidator(mock(AppSettingService.class), workspace),
				new SecureFileMove(new OrganizationMoveVerifier(new FileHashService()), pathRegistry),
				mock(PlatformTransactionManager.class), Clock.systemDefaultZone());
	}

	@Test
	void undoLocksTheOriginalRestorePathsNotOnlyTheExecutionRoot() {
		// A DEDUP_DELETE execution's source/target are both the quarantine root, yet
		// each file is restored to its ORIGINAL path (movement.sourcePath), which lies
		// outside the root. The lock must cover those paths too, or a concurrent
		// organization on the original tree would race the restore.
		Path quarantineRoot = tempDir.resolve("trash");
		Path original = tempDir.resolve("library").resolve("photo.jpg");
		Path quarantineCopy = quarantineRoot.resolve("exec-1").resolve("10__photo.jpg");

		Execution dedup = Execution.builder().id(1L).executionType(ExecutionType.DEDUP_DELETE)
				.status(ExecutionStatus.FINISHED).sourcePath(quarantineRoot.toString())
				.targetPath(quarantineRoot.toString()).build();

		CatalogFile catalogFile = catalogFile(10L, quarantineCopy);

		Movement movement = Movement.builder().id(100L).execution(dedup).catalogFile(catalogFile)
				.sourcePath(original.toAbsolutePath().normalize().toString())
				.targetPath(quarantineCopy.toAbsolutePath().normalize().toString()).status(MovementStatus.MOVED)
						.build();

		OperationLockService lockService = mock(OperationLockService.class);

		when(lockService.acquire(eq(ExecutionType.UNDO), any(Path[].class)))
				.thenReturn(mock(OperationLock.class));

		stubUndoExecution();

		OrganizationUndoService service = new OrganizationUndoService(executionRepository, catalogFileRepository,
				catalogFileLocationRepository,
				new OrganizationMovementLog(movementRepository, catalogFileRepository, executionErrorService),
				lockService, mock(OrganizationPathValidator.class),
				new SecureFileMove(new OrganizationMoveVerifier(new FileHashService()), pathRegistry),
				mock(PlatformTransactionManager.class), Clock.systemDefaultZone());

		when(executionRepository.findById(1L)).thenReturn(Optional.of(dedup));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR)))
				.thenReturn(List.of(movement));

		service.undo(1L);

		ArgumentCaptor<Path[]> lockedPaths = ArgumentCaptor.forClass(Path[].class);

		verify(lockService).acquire(eq(ExecutionType.UNDO), lockedPaths.capture());

		Assertions.assertThat(lockedPaths.getValue())
				.contains(PathUtils.normalizePath(original.toAbsolutePath().normalize().toString()));
	}

	@Test
	void undoShouldRejectAnExecutionThatDoesNotExist() {
		when(executionRepository.findById(99L)).thenReturn(Optional.empty());

		OrganizationUndoService service = serviceWithoutWorkspace();

		Assertions.assertThatThrownBy(() -> service.undo(99L)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Execution not found: 99");
	}

	/**
	 * Only organization and duplicate-quarantine moves are plain source-to-target
	 * movements; anything else has no reversal defined and must be refused instead
	 * of half-undone.
	 */
	@Test
	void undoShouldRejectAnExecutionTypeThatIsNotUndoable() {
		Execution inventory = Execution.builder().id(1L).executionType(ExecutionType.INVENTORY)
				.status(ExecutionStatus.FINISHED).build();

		when(executionRepository.findById(1L)).thenReturn(Optional.of(inventory));

		OrganizationUndoService service = serviceWithoutWorkspace();

		Assertions.assertThatThrownBy(() -> service.undo(1L)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Execution is not undoable: 1");

		verify(movementRepository, never()).findByExecutionIdAndStatusInOrderByIdDesc(any(), any());
	}

	/**
	 * A crash mid-loop must not leave the row open: an execution still holding a
	 * null {@code finishedAt} is read everywhere as the operation currently
	 * running, so it would show a phantom undo on every screen.
	 */
	@Test
	void aCrashMidUndoStillClosesTheExecution() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));
		Path source = sourceFolder.resolve("photo.jpg");
		Path target = Files.writeString(targetFolder.resolve("photo.jpg"), "content");

		CatalogFile catalogFile = catalogFile(10L, target);

		Movement movement = movement(100L, catalogFile, source, target, MovementStatus.MOVED);

		Execution undoExecution = Execution.builder().id(2L).build();

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(executionRepository.findById(2L)).thenReturn(Optional.of(undoExecution));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR)))
				.thenReturn(List.of(movement));

		OrganizationPathValidator refusingValidator = mock(OrganizationPathValidator.class);

		doThrow(new IllegalStateException("outside the workspace")).when(refusingValidator).validateAllowed(any(),
				any());

		stubUndoExecution();

		OrganizationUndoService service = new OrganizationUndoService(executionRepository, catalogFileRepository,
				catalogFileLocationRepository,
				new OrganizationMovementLog(movementRepository, catalogFileRepository, executionErrorService),
				operationLockService, refusingValidator,
				new SecureFileMove(new OrganizationMoveVerifier(new FileHashService()), pathRegistry),
				mock(PlatformTransactionManager.class), Clock.systemDefaultZone());

		// Registered after the service, whose shared stub also answers save(any()): the
		// undo row needs an id here, because closing it looks the row up by id.
		when(executionRepository.save(any())).thenAnswer(invocation -> {
			Execution saved = invocation.getArgument(0);

			return saved == null || saved.getId() == null ? undoExecution : saved;
		});

		Assertions.assertThatThrownBy(() -> service.undo(1L)).isInstanceOf(IllegalStateException.class);

		Assertions.assertThat(undoExecution.getStatus()).isEqualTo(ExecutionStatus.ERROR);
		Assertions.assertThat(undoExecution.getFinishedAt()).isNotNull();
	}

	/**
	 * The validation guards bail out before any path is resolved, so this variant
	 * skips the workspace stubbing that would otherwise go unused.
	 */
	private OrganizationUndoService serviceWithoutWorkspace() {
		stubUndoExecution();

		return new OrganizationUndoService(executionRepository, catalogFileRepository, catalogFileLocationRepository,
				new OrganizationMovementLog(movementRepository, catalogFileRepository, executionErrorService),
				operationLockService, mock(OrganizationPathValidator.class),
				new SecureFileMove(new OrganizationMoveVerifier(new FileHashService()), pathRegistry),
				mock(PlatformTransactionManager.class), Clock.systemDefaultZone());
	}

	private Execution execution() {
		return Execution.builder().id(1L).executionType(ExecutionType.ORGANIZATION).status(ExecutionStatus.FINISHED)
				.sourcePath(tempDir.resolve("source").toString()).targetPath(tempDir.resolve("target").toString())
				.build();
	}

	private CatalogFile catalogFile(Long id, Path path) {
		return CatalogFile.builder().id(id).fileKey(path.toAbsolutePath().normalize().toString())
				.fileName(path.getFileName().toString()).extension("jpg").sizeBytes(10L).modifiedAt(LocalDateTime.now())
				.importedAt(LocalDateTime.now()).fileType(FileType.PHOTO).lifecycleStatus(LifecycleStatus.ACTIVE)
				.build();
	}

	private CatalogFileLocation location(CatalogFile catalogFile, Path path) {
		return CatalogFileLocation.builder().catalogFile(catalogFile)
				.currentPath(path.toAbsolutePath().normalize().toString())
				.currentFolder(path.getParent().toAbsolutePath().normalize().toString())
				.originalPath(path.toAbsolutePath().normalize().toString())
				.originalFolder(path.getParent().toAbsolutePath().normalize().toString()).updatedAt(LocalDateTime.now())
				.build();
	}

	private Movement movement(Long id, CatalogFile catalogFile, Path source, Path target, MovementStatus status) {
		return Movement.builder().id(id).execution(execution()).catalogFile(catalogFile)
				.sourcePath(source.toAbsolutePath().normalize().toString())
				.targetPath(target.toAbsolutePath().normalize().toString()).status(status).build();
	}
}