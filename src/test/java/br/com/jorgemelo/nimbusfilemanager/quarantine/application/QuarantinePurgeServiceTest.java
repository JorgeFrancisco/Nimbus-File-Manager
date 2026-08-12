package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;

import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWriteOff;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.GrantingOperationLocks;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OwnershipLostException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.MovementPurgeResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantinePurgeResult;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionStopReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureLibraryFiles;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureFileMove;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationMoveVerifier;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;

class QuarantinePurgeServiceTest {

	private final MovementRepository movementRepository = mock(MovementRepository.class);
	private final QuarantinePurgePersistence purgePersistence = mock(QuarantinePurgePersistence.class);
	private final ExecutionCancellationService executionCancellationService = mock(ExecutionCancellationService.class);
	private final ExecutionStopReason executionStopReason = new ExecutionStopReason(executionCancellationService);
	private final QuarantineOperationLog purgeLog = mock(QuarantineOperationLog.class);
	private final QuarantinePurgeService service = new QuarantinePurgeService(movementRepository, purgePersistence,
			GrantingOperationLocks.granting(), executionStopReason, purgeLog,
					libraryFiles(),
					Clock.systemDefaultZone());

	@Test
	void isNoOpWhenRetentionDisabled() {
		QuarantinePurgeResult result = service.purgeOlderThan(0, execution(), owning());

		Assertions.assertThat(result.purged()).isZero();

		verify(movementRepository, never()).findByStatusAndReasonInAndMovedAtBeforeOrderByIdAsc(any(), any(), any(),
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

		QuarantinePurgeResult result = service.purgeOlderThan(90, execution(), owning());

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

		QuarantinePurgeResult result = service.purgeOlderThan(90, execution(), owning());

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

		QuarantinePurgeResult result = service.purgeOlderThan(90, execution(), owning());

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

		QuarantinePurgeResult result = service.purgeOlderThan(90, execution(), owning());

		Assertions.assertThat(result.errors()).isEqualTo(1);
		Assertions.assertThat(result.purged()).isZero();

		verify(purgePersistence, never()).deleteMovement(anyLong());
	}

	/**
	 * Counted apart from a plain skip because it is the outcome the user can act
	 * on: a conversion moving originals into quarantine holds the folder, and the
	 * screen has to say so instead of reporting a delete that did nothing.
	 */
	@Test
	void reportsTheItemAsBusyWhenAnotherOperationHoldsThePath(@TempDir Path tmp) throws Exception {
		Path file = Files.writeString(
				Files.createDirectories(tmp.resolve("trash").resolve("exec-1")).resolve("10__a.jpg"), "old");

		Movement movement = overdueMovement(4L, file);

		OperationLockService lockService = mock(OperationLockService.class);

		when(lockService.acquire(any(), any())).thenThrow(new OperationLockException("busy"));

		QuarantinePurgeService locked = new QuarantinePurgeService(movementRepository, purgePersistence, lockService,
				executionStopReason, purgeLog, libraryFiles(), Clock.systemDefaultZone());

		overdueReturns(movement);

		QuarantinePurgeResult result = locked.purgeOlderThan(90, execution(), owning());

		Assertions.assertThat(result.busy()).isEqualTo(1);
		Assertions.assertThat(result.skipped()).isZero();
		Assertions.assertThat(Files.exists(file)).isTrue();

		verify(purgePersistence, never()).deleteMovement(anyLong());
	}

	@Test
	void purgeSelectedDeletesFileAndRecordNow(@TempDir Path tmp) throws Exception {
		Path exec = Files.createDirectories(tmp.resolve("trash").resolve("exec-1"));
		Path file = Files.writeString(exec.resolve("10__a.jpg"), "content");

		UUID publicId = UUID.randomUUID();

		Movement movement = Movement.builder().id(1L).movementPublicId(publicId)
				.requestedTargetPath(PathUtils.normalize(file)).requestedSourcePath("ignored")
				.status(MovementStatus.MOVED).reason(MovementReason.DUPLICATE_QUARANTINED)
				.movedAt(Instant.now()).build();

		when(movementRepository.findByMovementPublicId(publicId)).thenReturn(Optional.of(movement));
		when(purgePersistence.deleteMovement(1L)).thenReturn(MovementPurgeResult.removed(9L));
		when(purgePersistence.deleteCatalogFileIfOrphan(9L)).thenReturn(true);

		QuarantinePurgeResult result = service.purgeSelected(List.of(publicId), execution(), owning());

		Assertions.assertThat(result.purged()).isEqualTo(1);
		Assertions.assertThat(result.catalogsFreed()).isEqualTo(1);
		Assertions.assertThat(Files.exists(file)).isFalse();
	}

	@Test
	void purgeSelectedSkipsUnknownOrRestoredItems() {
		UUID publicId = UUID.randomUUID();

		when(movementRepository.findByMovementPublicId(publicId)).thenReturn(Optional.empty());

		QuarantinePurgeResult result = service.purgeSelected(List.of(publicId), execution(), owning());

		Assertions.assertThat(result.skipped()).isEqualTo(1);

		verify(purgePersistence, never()).deleteMovement(anyLong());
	}

	/**
	 * The shortlist is a reading somebody else took, and it is not trusted: every
	 * file is looked at again here, so an item that came back keeps its record.
	 */
	@Test
	void cleanupAbsentRemovesGoneRecordsAndKeepsPresentOnes(@TempDir Path tmp) throws Exception {
		Path exec = Files.createDirectories(tmp.resolve("trash").resolve("exec-1"));
		Path present = Files.writeString(exec.resolve("11__present.jpg"), "here");
		Path absent = exec.resolve("10__gone.jpg");

		Movement presentMovement = shortlisted(1L, present);
		Movement absentMovement = shortlisted(2L, absent);

		when(purgePersistence.deleteMovement(2L)).thenReturn(MovementPurgeResult.removed(9L));

		int removed = service.cleanupAbsent(List.of(presentMovement.getMovementPublicId(), absentMovement.getMovementPublicId()),
				execution(), owning());

		Assertions.assertThat(removed).isEqualTo(1);
		Assertions.assertThat(Files.exists(present)).isTrue();

		verify(purgePersistence).deleteMovement(2L);
		verify(purgePersistence, never()).deleteMovement(1L);
	}

	@Test
	void purgeSelectedIsNoOpForNullOrEmptyInput() {
		Assertions.assertThat(service.purgeSelected(null, execution(), owning()).scanned()).isZero();
		Assertions.assertThat(service.purgeSelected(List.of(), execution(), owning()).scanned()).isZero();

		verify(movementRepository, never()).findByMovementPublicId(any());
	}

	@Test
	void purgeSelectedSkipsAMovementThatIsNoLongerQuarantined() {
		UUID publicId = UUID.randomUUID();

		Movement restored = Movement.builder().id(1L).movementPublicId(publicId).requestedTargetPath("ignored")
				.requestedSourcePath("ignored")
				.status(MovementStatus.SKIPPED).reason(MovementReason.DUPLICATE_QUARANTINED)
				.movedAt(Instant.now()).build();

		when(movementRepository.findByMovementPublicId(publicId)).thenReturn(Optional.of(restored));

		QuarantinePurgeResult result = service.purgeSelected(List.of(publicId), execution(), owning());

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

		QuarantinePurgeResult result = service.purgeOlderThan(90, execution(), owning());

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

		service.purgeOlderThan(90, execution(), owning());

		Assertions.assertThat(Files.exists(file)).isFalse();
		Assertions.assertThat(Files.exists(exec)).as("folder kept because a sibling remains").isTrue();
	}

	@Test
	void cleanupAbsentSkipsALockedItem(@TempDir Path tmp) {
		Path absent = tmp.resolve("trash").resolve("exec-1").resolve("10__gone.jpg");

		Movement movement = shortlisted(1L, absent);

		OperationLockService lockService = mock(OperationLockService.class);

		when(lockService.acquire(any(), any())).thenThrow(new OperationLockException("busy"));

		QuarantinePurgeService locked = new QuarantinePurgeService(movementRepository, purgePersistence, lockService,
				executionStopReason, purgeLog, libraryFiles(), Clock.systemDefaultZone());

		Assertions.assertThat(locked.cleanupAbsent(List.of(movement.getMovementPublicId()), execution(), owning())).isZero();

		verify(purgePersistence, never()).deleteMovement(anyLong());
	}

	/**
	 * The manual tally has to distinguish every purge outcome: a delete that also
	 * frees a catalog row, one the persistence refused (a race no-op) and one whose
	 * file could not be removed at all.
	 */
	@Test
	void purgeSelectedShouldCountCatalogsFreedSkippedAndErrorsSeparately(@TempDir Path tmp) throws Exception {
		Path exec = Files.createDirectories(tmp.resolve("trash").resolve("exec-1"));

		Path withCatalog = Files.writeString(exec.resolve("10__a.jpg"), "a");
		Path raced = Files.writeString(exec.resolve("11__b.jpg"), "b");

		Movement first = quarantined(1L, withCatalog);
		Movement second = quarantined(2L, raced);

		when(movementRepository.findByMovementPublicId(first.getMovementPublicId())).thenReturn(Optional.of(first));
		when(movementRepository.findByMovementPublicId(second.getMovementPublicId())).thenReturn(Optional.of(second));
		when(purgePersistence.deleteMovement(1L)).thenReturn(MovementPurgeResult.removed(9L));
		when(purgePersistence.deleteMovement(2L)).thenReturn(MovementPurgeResult.notRemoved());
		when(purgePersistence.deleteCatalogFileIfOrphan(9L)).thenReturn(true);

		QuarantinePurgeResult result = service.purgeSelected(List.of(first.getMovementPublicId(), second.getMovementPublicId()),
				execution(), owning());

		Assertions.assertThat(result.scanned()).isEqualTo(2);
		Assertions.assertThat(result.purged()).isEqualTo(1);
		Assertions.assertThat(result.catalogsFreed()).isEqualTo(1);
		Assertions.assertThat(result.skipped()).isEqualTo(1);
	}

	/**
	 * The record only disappears when the persistence actually removed it; a
	 * concurrent cleanup that got there first must not be counted again.
	 */
	@Test
	void cleanupAbsentShouldNotCountARecordAnotherRunAlreadyRemoved(@TempDir Path tmp) {
		Path absent = tmp.resolve("trash").resolve("exec-1").resolve("10__gone.jpg");

		Movement movement = shortlisted(1L, absent);

		when(purgePersistence.deleteMovement(1L)).thenReturn(MovementPurgeResult.notRemoved());

		Assertions.assertThat(service.cleanupAbsent(List.of(movement.getMovementPublicId()), execution(), owning())).isZero();
	}

	/**
	 * Deleting a user's file for good is the most destructive thing the application
	 * does; until now it left nothing but a log line.
	 */
	@Test
	void aPurgeRunsAsAnExecutionOfItsOwn(@TempDir Path tmp) throws Exception {
		Path exec = Files.createDirectories(tmp.resolve("trash").resolve("exec-1"));
		Path file = Files.writeString(exec.resolve("10__a.jpg"), "old");

		Movement movement = overdueMovement(1L, file);

		Execution purgeExecution = execution();

		overdueReturns(movement);
		when(purgePersistence.deleteMovement(1L)).thenReturn(MovementPurgeResult.removed(9L));

		service.purgeOlderThan(90, purgeExecution, owning());

		verify(purgeLog).finish(eq(ownership), eq(1), eq(1), eq(0), eq(0), any());
	}

	/**
	 * Whether anything is overdue is asked before the daily pass is queued, so
	 * getting here with nothing means the last item went in between. The row
	 * already exists and is closed saying so, rather than left looking like work
	 * still to come.
	 */
	@Test
	void aPassThatFindsNothingOverdueClosesTheRowItWasHanded() {
		Execution purgeExecution = execution();

		when(movementRepository.findByStatusAndReasonInAndMovedAtBeforeOrderByIdAsc(eq(MovementStatus.MOVED),
				eq(QuarantineConstants.QUARANTINED_REASONS), any(), any())).thenReturn(new PageImpl<>(List.of()));

		QuarantinePurgeResult result = service.purgeOlderThan(90, purgeExecution, owning());

		Assertions.assertThat(result.purged()).isZero();

		verify(purgeLog).finish(eq(ownership), eq(0), eq(0), eq(0), eq(0), any());
		verify(purgePersistence, never()).deleteMovement(anyLong());
	}

	/**
	 * Expunging is irreversible, so the wish to go on is confirmed before each
	 * file rather than after it: a cancel arriving mid-pass leaves everything it
	 * had not reached still in quarantine.
	 */
	@Test
	void stopsBeforeTheNextFileWhenSomebodyCancelsThePurge(@TempDir Path tmp) throws Exception {
		Path exec = Files.createDirectories(tmp.resolve("trash").resolve("exec-1"));
		Path first = Files.writeString(exec.resolve("10__a.jpg"), "old");
		Path second = Files.writeString(exec.resolve("11__b.jpg"), "old");

		Execution purgeExecution = execution();

		when(executionCancellationService.isCancelled(77L)).thenReturn(false, true);
		when(purgePersistence.deleteMovement(1L)).thenReturn(MovementPurgeResult.removed(9L));
		overdueReturns(overdueMovement(1L, first), overdueMovement(2L, second));

		QuarantinePurgeResult result = service.purgeOlderThan(90, purgeExecution, owning());

		Assertions.assertThat(result.purged()).isEqualTo(1);
		Assertions.assertThat(Files.exists(first)).isFalse();
		Assertions.assertThat(Files.exists(second)).isTrue();

		verify(purgeLog).stop(eq(ownership), eq(ExecutionStatus.CANCELLED), eq(2), eq(1), eq(0), eq(0), any());
		verify(purgeLog, never()).finish(any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
	}

	/**
	 * Standing on locks it no longer holds is not a failure of the purge, and it
	 * must not expunge one more file while it is unsure.
	 */
	@Test
	void stopsAsInterruptedWhenItNoLongerOwnsTheQuarantine(@TempDir Path tmp) throws Exception {
		Path exec = Files.createDirectories(tmp.resolve("trash").resolve("exec-1"));
		Path file = Files.writeString(exec.resolve("10__a.jpg"), "old");

		Execution purgeExecution = execution();

		ExecutionOwnership lost = mock(ExecutionOwnership.class);

		doThrow(new OwnershipLostException("the lease went away")).when(lost).assertMayGoOnWorking();
		overdueReturns(overdueMovement(1L, file));

		QuarantinePurgeResult result = service.purgeOlderThan(90, purgeExecution, lost);

		Assertions.assertThat(result.purged()).isZero();
		Assertions.assertThat(Files.exists(file)).isTrue();

		verify(purgeLog).stop(eq(lost), eq(ExecutionStatus.INTERRUPTED), eq(1), eq(0), eq(0), eq(0), any());
	}

	/**
	 * "0 apagados" on its own reads as a success, so a purge that deleted nothing
	 * says which of the three reasons stopped it - here, a conversion holding the
	 * quarantine folder while it moves originals into it.
	 */
	@Test
	void saysWhyNothingWasDeletedWhenAnotherOperationIsHoldingTheFiles(@TempDir Path tmp) {
		Path exec = tmp.resolve("trash").resolve("exec-1");

		OperationLockService lockService = mock(OperationLockService.class);

		when(lockService.acquire(any(), any())).thenThrow(new OperationLockException("busy"));

		QuarantinePurgeService locked = new QuarantinePurgeService(movementRepository, purgePersistence, lockService,
				executionStopReason, purgeLog, libraryFiles(), Clock.systemDefaultZone());

		Execution purgeExecution = execution();

		overdueReturns(overdueMovement(1L, exec.resolve("10__a.jpg")));

		locked.purgeOlderThan(90, purgeExecution, owning());

		ArgumentCaptor<ExecutionMessage> captor = ArgumentCaptor.forClass(ExecutionMessage.class);

		verify(purgeLog).finish(eq(ownership), anyInt(), eq(0), anyInt(), eq(0), captor.capture());

		Assertions.assertThat(captor.getValue().code()).isEqualTo("backend.quarantine.delete.busy");
	}

	/** The file that could not be deleted is named, not just counted. */
	@Test
	void aFileThatCouldNotBeDeletedIsRecordedAgainstThePurge(@TempDir Path tmp) throws Exception {
		Path exec = Files.createDirectories(tmp.resolve("trash").resolve("exec-1"));
		Path folderInTheWayOfDeletion = Files.createDirectories(exec.resolve("10__a.jpg"));

		Files.writeString(folderInTheWayOfDeletion.resolve("keeps-it-undeletable.txt"), "x");

		Movement movement = overdueMovement(1L, folderInTheWayOfDeletion);

		Execution purgeExecution = execution();

		overdueReturns(movement);

		QuarantinePurgeResult result = service.purgeOlderThan(90, purgeExecution, owning());

		Assertions.assertThat(result.errors()).isEqualTo(1);

		ArgumentCaptor<ExecutionMessage> captor = ArgumentCaptor.forClass(ExecutionMessage.class);

		verify(purgeLog).recordFailure(eq(purgeExecution), eq(folderInTheWayOfDeletion), any(IOException.class));
		verify(purgeLog).finish(eq(ownership), eq(1), eq(0), eq(0), eq(1), captor.capture());

		// Nothing was deleted, so the row says what the disk refused rather than the
		// four counters that describe a pass that worked.
		Assertions.assertThat(captor.getValue().code()).isEqualTo("backend.quarantine.delete.errors");
	}

	/**
	 * An id that left quarantine between the listing and the click is not a
	 * failure, but a delete that did nothing still has to say why - the screen
	 * shows the sentence in a dialog.
	 */
	@Test
	void saysWhyNothingWasDeletedWhenEveryItemHadAlreadyLeftQuarantine() {
		Execution purgeExecution = execution();

		UUID gone = UUID.randomUUID();

		when(movementRepository.findByMovementPublicId(gone)).thenReturn(Optional.empty());

		QuarantinePurgeResult result = service.purgeSelected(List.of(gone), purgeExecution, owning());

		Assertions.assertThat(result.purged()).isZero();
		Assertions.assertThat(result.skipped()).isEqualTo(1);

		ArgumentCaptor<ExecutionMessage> captor = ArgumentCaptor.forClass(ExecutionMessage.class);

		verify(purgeLog).finish(eq(ownership), eq(1), eq(0), eq(1), eq(0), captor.capture());

		Assertions.assertThat(captor.getValue().code()).isEqualTo("backend.quarantine.delete.skipped");
	}

	/**
	 * A crash mid-loop must not leave the row open: an unfinished execution is read
	 * everywhere as the operation currently running.
	 */
	@Test
	void aCrashMidPurgeStillClosesTheExecution(@TempDir Path tmp) throws Exception {
		Path exec = Files.createDirectories(tmp.resolve("trash").resolve("exec-1"));
		Path file = Files.writeString(exec.resolve("10__a.jpg"), "old");

		Movement movement = overdueMovement(1L, file);

		Execution purgeExecution = execution();

		overdueReturns(movement);
		when(purgePersistence.deleteMovement(1L)).thenThrow(new IllegalStateException("db down"));

		Assertions.assertThatThrownBy(() -> service.purgeOlderThan(90, purgeExecution, ownership))
				.isInstanceOf(IllegalStateException.class);

		verify(purgeLog).fail(eq(ownership), any());
		verify(purgeLog, never()).finish(any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
	}

	/**
	 * Clearing records is the quieter half of the purge, but it still ends
	 * quarantine entries for good, so it closes the same kind of row - one a worker
	 * claimed before the work began, rather than one opened on the way out.
	 */
	@Test
	void clearingAbsentRecordsClosesTheExecutionItWasHanded(@TempDir Path tmp) {
		Path absent = tmp.resolve("trash").resolve("exec-1").resolve("10__gone.jpg");

		Movement movement = shortlisted(1L, absent);

		Execution cleanupExecution = execution();

		when(purgePersistence.deleteMovement(1L)).thenReturn(MovementPurgeResult.removed(9L));

		Assertions.assertThat(service.cleanupAbsent(List.of(movement.getMovementPublicId()), cleanupExecution, owning()))
				.isEqualTo(1);

		verify(purgeLog).finish(eq(ownership), eq(1), eq(1), eq(0), eq(0), any());
	}

	/**
	 * Every id left quarantine between the reading and the claim: there is nothing
	 * to clear, and the row still says so rather than being left open.
	 */
	@Test
	void clearingCountsNothingWhenEveryShortlistedItemIsGoneFromQuarantine() {
		Execution cleanupExecution = execution();

		UUID movementId = UUID.randomUUID();

		when(movementRepository.findByMovementPublicId(movementId)).thenReturn(Optional.empty());

		Assertions.assertThat(service.cleanupAbsent(List.of(movementId), cleanupExecution, owning())).isZero();

		verify(purgePersistence, never()).deleteMovement(anyLong());
		verify(purgeLog).finish(eq(ownership), eq(0), eq(0), eq(0), eq(0), any());
	}

	/** An item kept under the lock is counted apart, and is not an error. */
	@Test
	void clearingCountsAKeptRecordApartFromAFailure(@TempDir Path tmp) {
		Path absent = tmp.resolve("trash").resolve("exec-1").resolve("10__gone.jpg");

		Movement movement = shortlisted(1L, absent);

		OperationLockService lockService = mock(OperationLockService.class);

		when(lockService.acquire(any(), any())).thenThrow(new OperationLockException("busy"));

		QuarantinePurgeService locked = new QuarantinePurgeService(movementRepository, purgePersistence, lockService,
				executionStopReason, purgeLog, libraryFiles(), Clock.systemDefaultZone());

		Execution cleanupExecution = execution();

		Assertions.assertThat(locked.cleanupAbsent(List.of(movement.getMovementPublicId()), cleanupExecution, owning()))
				.isZero();

		verify(purgeLog).finish(eq(ownership), eq(1), eq(0), eq(1), eq(0), any());
	}

	private Movement quarantined(long id, Path target) {
		return Movement.builder().id(id).movementPublicId(UUID.randomUUID()).requestedTargetPath(PathUtils.normalize(target))
				.requestedSourcePath("ignored").status(MovementStatus.MOVED).reason(MovementReason.DUPLICATE_QUARANTINED)
				.movedAt(Instant.now()).build();
	}

	private void overdueReturns(Movement... movements) {
		when(movementRepository.findByStatusAndReasonInAndMovedAtBeforeOrderByIdAsc(eq(MovementStatus.MOVED),
				eq(QuarantineConstants.QUARANTINED_REASONS), any(), any()))
						.thenReturn(new PageImpl<>(List.of(movements)));
	}

	private Movement overdueMovement(long id, Path target) {
		return Movement.builder().id(id).requestedTargetPath(PathUtils.normalize(target)).requestedSourcePath("ignored")
				.status(MovementStatus.MOVED).reason(MovementReason.DUPLICATE_QUARANTINED)
				.movedAt(Instant.now().minus(Duration.ofDays(200))).build();
	}

	/**
	 * An item on a cleanup shortlist. What arrives is a list of ids read somewhere
	 * else, so the repository has to answer for this movement's public id - which
	 * is also where "is it still quarantined?" is asked again.
	 */
	private Movement shortlisted(long id, Path target) {
		Movement movement = Movement.builder().id(id).movementPublicId(UUID.randomUUID())
				.requestedTargetPath(PathUtils.normalize(target)).requestedSourcePath("ignored").status(MovementStatus.MOVED)
				.reason(MovementReason.DUPLICATE_QUARANTINED).movedAt(Instant.now()).build();

		when(movementRepository.findByMovementPublicId(movement.getMovementPublicId())).thenReturn(Optional.of(movement));

		return movement;
	}

	/**
	 * A crash halfway through the cleanup used to leave the execution open for
	 * good, showing as running on the screen forever: it now ends as a failure
	 * carrying the reason, and the caller still sees the error.
	 */
	@Test
	void aCleanupThatCrashesEndsTheExecutionAsAFailure(@TempDir Path tmp) {
		Path absent = tmp.resolve("trash").resolve("exec-1").resolve("10__gone.jpg");

		Movement movement = shortlisted(1L, absent);

		Execution cleanupExecution = execution();

		List<UUID> shortlist = List.of(movement.getMovementPublicId());

		when(purgePersistence.deleteMovement(1L)).thenThrow(new IllegalStateException("the database went away"));

		Assertions.assertThatThrownBy(() -> service.cleanupAbsent(shortlist, cleanupExecution, ownership))
				.isInstanceOf(IllegalStateException.class);

		verify(purgeLog).fail(ownership, "the database went away");
	}

	/**
	 * The row a worker claimed and the ownership of the paths it locked: the purge
	 * is handed both rather than opening the first and doing without the second.
	 */
	private Execution execution() {
		return Execution.builder().id(77L).executionType(ExecutionType.QUARANTINE_PURGE)
				.status(ExecutionStatus.RUNNING).build();
	}

	/**
	 * The taking every write about the row is made under. A real one rather than a
	 * stub, so a write refused for having lost its turn is refused here too.
	 */
	private final ExecutionOwnership ownership = Takings.owning(1L);

	private ExecutionOwnership owning() {
		return ownership;
	}

	/**
	 * The real port: the purge has to actually remove the file from disk and
	 * announce it, and both are what the assertions below look at.
	 */
	private static SecureLibraryFiles libraryFiles() {
		SelfWrittenPathRegistry registry = new SelfWriteOff();

		return new SecureLibraryFiles(new SecureFileMove(new OrganizationMoveVerifier(new FileHashService()),
				registry), registry);
	}
}