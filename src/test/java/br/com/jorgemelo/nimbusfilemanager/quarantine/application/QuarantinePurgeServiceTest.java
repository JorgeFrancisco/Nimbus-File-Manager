package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.PageImpl;

import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.MovementPurgeResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantinePurgeResult;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

class QuarantinePurgeServiceTest {

	private final MovementRepository movementRepository = mock(MovementRepository.class);
	private final QuarantinePurgePersistence purgePersistence = mock(QuarantinePurgePersistence.class);
	private final QuarantinePurgeService service = new QuarantinePurgeService(movementRepository, purgePersistence,
			new OperationLockService(), Clock.systemDefaultZone());

	@Test
	void isNoOpWhenRetentionDisabled() {
		QuarantinePurgeResult result = service.purgeOlderThan(0);

		Assertions.assertThat(result.purged()).isZero();

		verify(movementRepository, never()).findByStatusAndReasonAndMovedAtBeforeOrderByIdAsc(any(), any(), any(),
				any());
	}

	@Test
	void deletesOldFileCleansRecordAndFreesCatalog(@TempDir Path tmp) throws Exception {
		Path exec = Files.createDirectories(tmp.resolve("trash").resolve("exec-1"));
		Path file = Files.writeString(exec.resolve("10__a.jpg"), "old");

		Movement movement = overdueMovement(1L, file);

		overdueReturns(movement);
		when(purgePersistence.deleteMovement(1L)).thenReturn(MovementPurgeResult.removed(9L));
		when(purgePersistence.deleteCatalogFileIfOrphan(9L)).thenReturn(true);

		QuarantinePurgeResult result = service.purgeOlderThan(90);

		Assertions.assertThat(result.purged()).isEqualTo(1);
		Assertions.assertThat(result.catalogsFreed()).isEqualTo(1);
		Assertions.assertThat(Files.exists(file)).isFalse();
		Assertions.assertThat(Files.exists(exec)).isFalse();
	}

	@Test
	void reconcilesWhenFileIsAlreadyGone(@TempDir Path tmp) {
		Path file = tmp.resolve("trash").resolve("exec-1").resolve("10__a.jpg");

		Movement movement = overdueMovement(2L, file);

		overdueReturns(movement);
		// File already gone but the row is still a quarantined item: a legitimate
		// reconciliation - the row is removed (with no media file to free) and counted.
		when(purgePersistence.deleteMovement(2L)).thenReturn(MovementPurgeResult.removed(null));

		QuarantinePurgeResult result = service.purgeOlderThan(90);

		Assertions.assertThat(result.purged()).isEqualTo(1);
		Assertions.assertThat(result.catalogsFreed()).isZero();

		verify(purgePersistence).deleteMovement(2L);
	}

	@Test
	void doesNotCountARaceNoOpAsPurged(@TempDir Path tmp) {
		// The item was restored concurrently between listing and now: its file is gone
		// from quarantine and the row is no longer a quarantined item. The purge must
		// count it as skipped, never as purged.
		Path file = tmp.resolve("trash").resolve("exec-1").resolve("10__a.jpg");

		Movement movement = overdueMovement(5L, file);

		overdueReturns(movement);
		when(purgePersistence.deleteMovement(5L)).thenReturn(MovementPurgeResult.notRemoved());

		QuarantinePurgeResult result = service.purgeOlderThan(90);

		Assertions.assertThat(result.purged()).isZero();
		Assertions.assertThat(result.skipped()).isEqualTo(1);
		Assertions.assertThat(result.catalogsFreed()).isZero();
	}

	@Test
	void keepsRecordWhenPhysicalDeleteFails(@TempDir Path tmp) throws Exception {
		// A non-empty directory at the quarantine path cannot be deleted by
		// Files.delete.
		Path stuck = Files.createDirectories(tmp.resolve("trash").resolve("exec-1").resolve("10__a.jpg"));

		Files.writeString(stuck.resolve("child"), "x");

		Movement movement = overdueMovement(3L, stuck);

		overdueReturns(movement);

		QuarantinePurgeResult result = service.purgeOlderThan(90);

		Assertions.assertThat(result.errors()).isEqualTo(1);
		Assertions.assertThat(result.purged()).isZero();

		verify(purgePersistence, never()).deleteMovement(anyLong());
	}

	@Test
	void skipsItemWhenPathIsLockedByAnotherOperation(@TempDir Path tmp) throws Exception {
		Path file = Files.writeString(
				Files.createDirectories(tmp.resolve("trash").resolve("exec-1")).resolve("10__a.jpg"), "old");

		Movement movement = overdueMovement(4L, file);

		OperationLockService lockService = mock(OperationLockService.class);

		when(lockService.acquire(any(), any())).thenThrow(new OperationLockException("busy"));

		QuarantinePurgeService locked = new QuarantinePurgeService(movementRepository, purgePersistence, lockService,
				Clock.systemDefaultZone());

		overdueReturns(movement);

		QuarantinePurgeResult result = locked.purgeOlderThan(90);

		Assertions.assertThat(result.skipped()).isEqualTo(1);
		Assertions.assertThat(Files.exists(file)).isTrue();

		verify(purgePersistence, never()).deleteMovement(anyLong());
	}

	@Test
	void purgeSelectedDeletesFileAndRecordNow(@TempDir Path tmp) throws Exception {
		Path exec = Files.createDirectories(tmp.resolve("trash").resolve("exec-1"));
		Path file = Files.writeString(exec.resolve("10__a.jpg"), "content");

		UUID publicId = UUID.randomUUID();

		Movement movement = Movement.builder().id(1L).publicId(publicId).targetPath(PathUtils.normalize(file))
				.sourcePath("ignored").status(MovementStatus.MOVED).reason(MovementReason.DUPLICATE_QUARANTINED)
				.movedAt(LocalDateTime.now()).build();

		when(movementRepository.findByPublicId(publicId)).thenReturn(Optional.of(movement));
		when(purgePersistence.deleteMovement(1L)).thenReturn(MovementPurgeResult.removed(9L));
		when(purgePersistence.deleteCatalogFileIfOrphan(9L)).thenReturn(true);

		QuarantinePurgeResult result = service.purgeSelected(List.of(publicId));

		Assertions.assertThat(result.purged()).isEqualTo(1);
		Assertions.assertThat(result.catalogsFreed()).isEqualTo(1);
		Assertions.assertThat(Files.exists(file)).isFalse();
	}

	@Test
	void purgeSelectedSkipsUnknownOrRestoredItems() {
		UUID publicId = UUID.randomUUID();

		when(movementRepository.findByPublicId(publicId)).thenReturn(Optional.empty());

		QuarantinePurgeResult result = service.purgeSelected(List.of(publicId));

		Assertions.assertThat(result.skipped()).isEqualTo(1);

		verify(purgePersistence, never()).deleteMovement(anyLong());
	}

	@Test
	void cleanupAbsentRemovesGoneRecordsAndKeepsPresentOnes(@TempDir Path tmp) throws Exception {
		Path exec = Files.createDirectories(tmp.resolve("trash").resolve("exec-1"));
		Path present = Files.writeString(exec.resolve("11__present.jpg"), "here");
		Path absent = exec.resolve("10__gone.jpg");

		Movement presentMovement = overdueMovement(1L, present);
		Movement absentMovement = overdueMovement(2L, absent);

		when(movementRepository.findByStatusAndReasonOrderByIdDesc(eq(MovementStatus.MOVED),
				eq(MovementReason.DUPLICATE_QUARANTINED), any()))
				.thenReturn(new PageImpl<>(List.of(presentMovement, absentMovement)));
		when(purgePersistence.deleteMovement(2L)).thenReturn(MovementPurgeResult.removed(9L));

		int removed = service.cleanupAbsent();

		Assertions.assertThat(removed).isEqualTo(1);
		Assertions.assertThat(Files.exists(present)).isTrue();

		verify(purgePersistence).deleteMovement(2L);
		verify(purgePersistence, never()).deleteMovement(1L);
	}

	@Test
	void purgeSelectedIsNoOpForNullOrEmptyInput() {
		Assertions.assertThat(service.purgeSelected(null).scanned()).isZero();
		Assertions.assertThat(service.purgeSelected(List.of()).scanned()).isZero();

		verify(movementRepository, never()).findByPublicId(any());
	}

	@Test
	void purgeSelectedSkipsAMovementThatIsNoLongerQuarantined() {
		UUID publicId = UUID.randomUUID();

		Movement restored = Movement.builder().id(1L).publicId(publicId).targetPath("ignored").sourcePath("ignored")
				.status(MovementStatus.SKIPPED).reason(MovementReason.DUPLICATE_QUARANTINED)
				.movedAt(LocalDateTime.now()).build();

		when(movementRepository.findByPublicId(publicId)).thenReturn(Optional.of(restored));

		QuarantinePurgeResult result = service.purgeSelected(List.of(publicId));

		Assertions.assertThat(result.skipped()).isEqualTo(1);
		Assertions.assertThat(result.purged()).isZero();

		verify(purgePersistence, never()).deleteMovement(anyLong());
	}

	@Test
	void keepsTheCatalogRowWhenTheBestEffortCleanupThrows(@TempDir Path tmp) throws Exception {
		Path exec = Files.createDirectories(tmp.resolve("trash").resolve("exec-1"));
		Path file = Files.writeString(exec.resolve("10__a.jpg"), "old");

		Movement movement = overdueMovement(1L, file);

		overdueReturns(movement);
		when(purgePersistence.deleteMovement(1L)).thenReturn(MovementPurgeResult.removed(9L));
		when(purgePersistence.deleteCatalogFileIfOrphan(9L)).thenThrow(new RuntimeException("foreign key"));

		QuarantinePurgeResult result = service.purgeOlderThan(90);

		Assertions.assertThat(result.purged()).isEqualTo(1);
		Assertions.assertThat(result.catalogsFreed()).isZero();
		Assertions.assertThat(Files.exists(file)).isFalse();
	}

	@Test
	void leavesAQuarantineFolderThatStillHasOtherFiles(@TempDir Path tmp) throws Exception {
		Path exec = Files.createDirectories(tmp.resolve("trash").resolve("exec-1"));
		Path file = Files.writeString(exec.resolve("10__a.jpg"), "old");

		Files.writeString(exec.resolve("11__sibling.jpg"), "keep");

		Movement movement = overdueMovement(1L, file);

		overdueReturns(movement);
		when(purgePersistence.deleteMovement(1L)).thenReturn(MovementPurgeResult.removed(null));

		service.purgeOlderThan(90);

		Assertions.assertThat(Files.exists(file)).isFalse();
		Assertions.assertThat(Files.exists(exec)).as("folder kept because a sibling remains").isTrue();
	}

	@Test
	void cleanupAbsentSkipsALockedItem(@TempDir Path tmp) {
		Path absent = tmp.resolve("trash").resolve("exec-1").resolve("10__gone.jpg");

		Movement movement = overdueMovement(1L, absent);

		OperationLockService lockService = mock(OperationLockService.class);

		when(lockService.acquire(any(), any())).thenThrow(new OperationLockException("busy"));

		QuarantinePurgeService locked = new QuarantinePurgeService(movementRepository, purgePersistence, lockService,
				Clock.systemDefaultZone());

		when(movementRepository.findByStatusAndReasonOrderByIdDesc(eq(MovementStatus.MOVED),
				eq(MovementReason.DUPLICATE_QUARANTINED), any())).thenReturn(new PageImpl<>(List.of(movement)));

		Assertions.assertThat(locked.cleanupAbsent()).isZero();

		verify(purgePersistence, never()).deleteMovement(anyLong());
	}

	private void overdueReturns(Movement movement) {
		when(movementRepository.findByStatusAndReasonAndMovedAtBeforeOrderByIdAsc(eq(MovementStatus.MOVED),
				eq(MovementReason.DUPLICATE_QUARANTINED), any(), any())).thenReturn(new PageImpl<>(List.of(movement)));
	}

	private Movement overdueMovement(long id, Path target) {
		return Movement.builder().id(id).targetPath(PathUtils.normalize(target)).sourcePath("ignored")
				.status(MovementStatus.MOVED).reason(MovementReason.DUPLICATE_QUARANTINED)
				.movedAt(LocalDateTime.now().minusDays(200)).build();
	}
}