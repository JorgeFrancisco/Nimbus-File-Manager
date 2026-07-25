package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateDeletionPersistence;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationMoveVerifier;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureFileMove;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineItemResponse;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreBatchResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreOptions;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.ConflictResolution;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.RestoreOutcome;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

class QuarantineServiceTest {

	private final MovementRepository movementRepository = mock(MovementRepository.class);
	private final DuplicateDeletionPersistence persistence = mock(DuplicateDeletionPersistence.class);
	private final QuarantineService service = new QuarantineService(movementRepository, persistence,
			new SecureFileMove(new OrganizationMoveVerifier(new FileHashService())), new OperationLockService());

	@Test
	void listsQuarantinedFilesWithLiveOriginAndConflictFlags(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		when(movementRepository.findByStatusAndReasonOrderByIdDesc(eq(MovementStatus.MOVED),
				eq(MovementReason.DUPLICATE_QUARANTINED), any())).thenReturn(new PageImpl<>(List.of(movement)));

		List<QuarantineItemResponse> items = service.list(PageRequest.of(0, 50)).getContent();

		Assertions.assertThat(items).hasSize(1);

		QuarantineItemResponse item = items.get(0);

		Assertions.assertThat(item.fileName()).isEqualTo("a.jpg");
		Assertions.assertThat(item.presentInQuarantine()).isTrue();
		Assertions.assertThat(item.originFolderExists()).isTrue();
		Assertions.assertThat(item.conflict()).isFalse();
	}

	@Test
	void restoresFileBackToOriginalPath(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path original = origin.resolve("a.jpg");
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(original, quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreResult result = service.restore(movement.getPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.RESTORED.name());
		Assertions.assertThat(result.success()).isTrue();
		Assertions.assertThat(Files.exists(original)).isTrue();
		Assertions.assertThat(Files.exists(quarantine)).isFalse();

		verify(persistence).applyRestore(eq(movement), any());
	}

	@Test
	void blocksWhenDestinationAlreadyHasAFile(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path original = Files.writeString(origin.resolve("a.jpg"), "existing");
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(original, quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreResult result = service.restore(movement.getPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.CONFLICT.name());
		Assertions.assertThat(Files.exists(quarantine)).isTrue();
		Assertions.assertThat(Files.readString(original)).isEqualTo("existing");

		verify(persistence, never()).applyRestore(any(), any());
	}

	@Test
	void restoresUnderNumberedNameWhenAskedToRename(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path original = Files.writeString(origin.resolve("a.jpg"), "existing");
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(original, quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreResult result = service.restore(movement.getPublicId(),
				new QuarantineRestoreOptions(null, ConflictResolution.RENAME));

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.RESTORED.name());
		Assertions.assertThat(Files.exists(original)).isTrue();
		Assertions.assertThat(Files.exists(origin.resolve("a (1).jpg"))).isTrue();
		Assertions.assertThat(Files.exists(quarantine)).isFalse();
	}

	@Test
	void reportsOriginMissingWhenOriginalFolderIsGone(@TempDir Path tmp) throws Exception {
		Path original = tmp.resolve("gone").resolve("a.jpg");
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(original, quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreResult result = service.restore(movement.getPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.ORIGIN_MISSING.name());
		Assertions.assertThat(Files.exists(quarantine)).isTrue();

		verify(persistence, never()).applyRestore(any(), any());
	}

	@Test
	void restoresIntoChosenAlternateFolder(@TempDir Path tmp) throws Exception {
		Path original = tmp.resolve("gone").resolve("a.jpg");
		Path alternate = Files.createDirectories(tmp.resolve("elsewhere"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(original, quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreResult result = service.restore(movement.getPublicId(),
				new QuarantineRestoreOptions(alternate, ConflictResolution.BLOCK));

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.RESTORED.name());
		Assertions.assertThat(Files.exists(alternate.resolve("a.jpg"))).isTrue();
		Assertions.assertThat(Files.exists(quarantine)).isFalse();
	}

	@Test
	void reportsMissingWhenQuarantineCopyIsGone(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = tmp.resolve("trash").resolve("exec-1").resolve("10__a.jpg");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreResult result = service.restore(movement.getPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.MISSING_IN_QUARANTINE.name());

		verify(persistence, never()).applyRestore(any(), any());
	}

	@Test
	void refusesToRestoreWhenTheQuarantineCopyIsNotAPhysicalFile(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		// A .lnk shortcut in quarantine (rejected by PhysicalFilePolicy): the restore
		// must be
		// refused, exactly like the forward path refuses to quarantine a link/shortcut.
		Path quarantine = writeQuarantineCopy(tmp, "10__a.lnk", "shortcut-bytes");

		Movement movement = quarantineMovement(origin.resolve("a.lnk"), quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreResult result = service.restore(movement.getPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.ERROR.name());
		// The item stays in quarantine; it is never "restored" as a link into the
		// library.
		Assertions.assertThat(Files.exists(quarantine)).isTrue();

		verify(persistence, never()).applyRestore(any(), any());
	}

	@Test
	void batchRestoreCountsRestoredAndConflicts(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path freeOriginal = origin.resolve("free.jpg");
		Path takenOriginal = Files.writeString(origin.resolve("taken.jpg"), "existing");

		Path freeQuarantine = writeQuarantineCopy(tmp, "10__free.jpg", "one");
		Path takenQuarantine = writeQuarantineCopy(tmp, "11__taken.jpg", "two");

		Movement free = quarantineMovement(freeOriginal, freeQuarantine);
		Movement taken = quarantineMovement(takenOriginal, takenQuarantine);

		when(movementRepository.findByPublicId(free.getPublicId())).thenReturn(Optional.of(free));
		when(movementRepository.findByPublicId(taken.getPublicId())).thenReturn(Optional.of(taken));

		QuarantineRestoreBatchResult result = service.restoreMany(List.of(free.getPublicId(), taken.getPublicId()));

		Assertions.assertThat(result.total()).isEqualTo(2);
		Assertions.assertThat(result.restored()).isEqualTo(1);
		Assertions.assertThat(result.conflicts()).isEqualTo(1);
		Assertions.assertThat(Files.exists(freeOriginal)).isTrue();
		Assertions.assertThat(Files.exists(takenQuarantine)).isTrue();
	}

	@Test
	void returnsErrorWhenMovementNotFound() {
		UUID id = UUID.randomUUID();

		when(movementRepository.findByPublicId(id)).thenReturn(Optional.empty());

		QuarantineRestoreResult result = service.restore(id, QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.ERROR.name());
		Assertions.assertThat(result.success()).isFalse();
	}

	@Test
	void returnsErrorWhenItemIsNoLongerQuarantined(@TempDir Path tmp) throws Exception {
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(tmp.resolve("library").resolve("a.jpg"), quarantine);

		movement.setStatus(MovementStatus.UNDONE);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreResult result = service.restore(movement.getPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.ERROR.name());

		verify(persistence, never()).applyRestore(any(), any());
	}

	@Test
	void keepsFileInQuarantineWhenResolutionIsSkip(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreResult result = service.restore(movement.getPublicId(),
				new QuarantineRestoreOptions(null, ConflictResolution.SKIP));

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.SKIPPED.name());
		Assertions.assertThat(Files.exists(quarantine)).isTrue();

		verify(persistence, never()).applyRestore(any(), any());
	}

	@Test
	void rollsFileBackToQuarantineWhenCatalogUpdateFails(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path original = origin.resolve("a.jpg");
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(original, quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));
		doThrow(new IllegalStateException("db down")).when(persistence).applyRestore(any(), any());

		QuarantineRestoreResult result = service.restore(movement.getPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.ERROR.name());
		Assertions.assertThat(Files.exists(quarantine)).isTrue();
		Assertions.assertThat(Files.exists(original)).isFalse();
	}

	@Test
	void leavesFileOrphanedAtDestinationWhenCatalogAndRollbackBothFail(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path original = origin.resolve("a.jpg");
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(original, quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));
		// The catalog update fails AND re-creates the quarantine copy, so the physical
		// roll-back (which never overwrites) cannot move the file back to quarantine.
		doAnswer(_ -> {
			Files.writeString(quarantine, "blocker");
			throw new IllegalStateException("db down");
		}).when(persistence).applyRestore(any(), any());

		QuarantineRestoreResult result = service.restore(movement.getPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.ERROR.name());
		// Roll-back could not put it back, so the restored file stays orphaned at the
		// origin.
		Assertions.assertThat(Files.exists(original)).isTrue();
		Assertions.assertThat(Files.readString(quarantine)).isEqualTo("blocker");
	}

	@Test
	void reportsLockedWhenAnotherOperationHoldsThePath(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		OperationLockService lockService = mock(OperationLockService.class);

		when(lockService.acquire(any(ExecutionType.class), any(Path.class), any(Path.class)))
				.thenThrow(new OperationLockException("busy"));

		QuarantineService locked = new QuarantineService(movementRepository, persistence,
				new SecureFileMove(new OrganizationMoveVerifier(new FileHashService())), lockService);

		QuarantineRestoreResult result = locked.restore(movement.getPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.LOCKED.name());
		Assertions.assertThat(Files.exists(quarantine)).isTrue();
	}

	@Test
	void reportsErrorWhenTheSecureMoveFails(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		SecureFileMove failingMove = mock(SecureFileMove.class);

		doThrow(new IOException("disk full")).when(failingMove).move(any(), any(), anyBoolean());

		QuarantineService failing = new QuarantineService(movementRepository, persistence, failingMove,
				new OperationLockService());

		QuarantineRestoreResult result = failing.restore(movement.getPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.ERROR.name());

		verify(persistence, never()).applyRestore(any(), any());
	}

	@Test
	void listFlagsMissingOriginAndFallsBackToDiskSize(@TempDir Path tmp) throws Exception {
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "abcd");

		Movement movement = quarantineMovement(tmp.resolve("gone").resolve("a.jpg"), quarantine);

		when(movement.getCatalogFile().getSizeBytes()).thenReturn(null);

		when(movementRepository.findByStatusAndReasonOrderByIdDesc(eq(MovementStatus.MOVED),
				eq(MovementReason.DUPLICATE_QUARANTINED), any())).thenReturn(new PageImpl<>(List.of(movement)));

		QuarantineItemResponse item = service.list(PageRequest.of(0, 50)).getContent().get(0);

		Assertions.assertThat(item.originFolderExists()).isFalse();
		Assertions.assertThat(item.presentInQuarantine()).isTrue();
		Assertions.assertThat(item.sizeBytes()).isEqualTo(4L);
		Assertions.assertThat(item.sizeLabel()).isEqualTo("4 B");
	}

	@Test
	void listBuildsMediaUrlsForImages(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "img");

		UUID publicId = UUID.randomUUID();

		Movement movement = imageMovement(origin.resolve("a.jpg"), quarantine, publicId);

		when(movementRepository.findByStatusAndReasonOrderByIdDesc(eq(MovementStatus.MOVED),
				eq(MovementReason.DUPLICATE_QUARANTINED), any())).thenReturn(new PageImpl<>(List.of(movement)));

		QuarantineItemResponse item = service.list(PageRequest.of(0, 50)).getContent().get(0);

		Assertions.assertThat(item.image()).isTrue();
		Assertions.assertThat(item.mediaPublicId()).isEqualTo(publicId);
		Assertions.assertThat(item.previewUrl()).isEqualTo("/api/media/" + publicId + "/content");
	}

	@Test
	void restoreManyRestoresEachSelectedFile(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreBatchResult result = service.restoreMany(List.of(movement.getPublicId()));

		Assertions.assertThat(result.total()).isEqualTo(1);
		Assertions.assertThat(result.restored()).isEqualTo(1);
		Assertions.assertThat(Files.exists(origin.resolve("a.jpg"))).isTrue();
	}

	@Test
	void restoreManyWithNoSelectionIsNoOp() {
		QuarantineRestoreBatchResult result = service.restoreMany(List.of());

		Assertions.assertThat(result.total()).isZero();
		Assertions.assertThat(result.restored()).isZero();
	}

	private Movement imageMovement(Path original, Path quarantine, UUID publicId) {
		Execution execution = mock(Execution.class);

		when(execution.getPublicId()).thenReturn(UUID.randomUUID());

		CatalogFile catalogFile = mock(CatalogFile.class);

		when(catalogFile.getFileType()).thenReturn(FileType.PHOTO);
		when(catalogFile.getSizeBytes()).thenReturn(10L);
		when(catalogFile.getPublicId()).thenReturn(publicId);

		return Movement.builder().publicId(UUID.randomUUID()).execution(execution).catalogFile(catalogFile)
				.sourcePath(PathUtils.normalize(original)).targetPath(PathUtils.normalize(quarantine))
				.status(MovementStatus.MOVED).reason(MovementReason.DUPLICATE_QUARANTINED).movedAt(LocalDateTime.now())
				.build();
	}

	private Path writeQuarantineCopy(Path tmp, String name, String content) throws Exception {
		Path folder = Files.createDirectories(tmp.resolve("trash").resolve("exec-1"));

		return Files.writeString(folder.resolve(name), content);
	}

	private Movement quarantineMovement(Path original, Path quarantine) {
		Execution execution = mock(Execution.class);

		when(execution.getPublicId()).thenReturn(UUID.randomUUID());

		CatalogFile catalogFile = mock(CatalogFile.class);

		when(catalogFile.getFileType()).thenReturn(null);
		when(catalogFile.getSizeBytes()).thenReturn(123L);

		return Movement.builder().publicId(UUID.randomUUID()).execution(execution).catalogFile(catalogFile)
				.sourcePath(PathUtils.normalize(original)).targetPath(PathUtils.normalize(quarantine))
				.status(MovementStatus.MOVED).reason(MovementReason.DUPLICATE_QUARANTINED).movedAt(LocalDateTime.now())
				.build();
	}
}