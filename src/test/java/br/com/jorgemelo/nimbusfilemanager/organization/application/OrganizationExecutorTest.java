package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
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
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationFallbackMode;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationSubdivision;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MoveBaseline;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationItem;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPlan;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationSummary;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
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
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

@ExtendWith(MockitoExtension.class)
class OrganizationExecutorTest {

	private final SelfWrittenPathRegistry pathRegistry = new SelfWrittenPathRegistry(Clock.systemDefaultZone());

	@TempDir
	Path tempDir;

	@Mock
	private OrganizationPlanner organizationPlanner;

	@Mock
	private ExecutionRepository executionRepository;

	@Mock
	private CatalogFileRepository catalogFileRepository;

	@Mock
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Mock
	private MovementRepository movementRepository;

	@Mock
	private ExecutionProgressService executionProgressService;

	private final OperationLockService operationLockService = new OperationLockService();

	private final ExecutionCancellationService executionCancellationService = new ExecutionCancellationService();

	@Test
	void executeShouldRejectPlanWithConflictsWhenNotAllowed() {
		when(executionRepository.save(any())).thenAnswer(invocation -> {
			Execution execution = invocation.getArgument(0);
			execution.setId(1L);
			return execution;
		});

		when(organizationPlanner.preview(any())).thenReturn(new OrganizationPlan("source", "target",
				OrganizationLayout.DEFAULT, false, new OrganizationSummary(2, 2, 0, 0, 2, 100, 1, 1, 0), List.of()));

		var response = executor().execute(request(tempDir.resolve("source"), tempDir.resolve("target"), false, false));

		Assertions.assertThat(response.rejected()).isTrue();
		Assertions.assertThat(response.status()).isEqualTo(ExecutionStatus.REJECTED.name());
		Assertions.assertThat(response.errors()).isZero();
	}

	@Test
	void executeShouldRejectWhenSourcePathIsAlreadyLocked() {
		Path sourceFolder = tempDir.resolve("source");
		Path targetFolder = tempDir.resolve("target");

		CountDownLatch lockAcquired = new CountDownLatch(1);
		CountDownLatch releaseLock = new CountDownLatch(1);

		when(executionRepository.save(any())).thenAnswer(invocation -> {
			Execution execution = invocation.getArgument(0);
			execution.setId(1L);
			return execution;
		});

		Thread lockThread = holdLock(sourceFolder, lockAcquired, releaseLock);

		try {
			Assertions.assertThat(lockAcquired.await(2, TimeUnit.SECONDS)).isTrue();

			var response = executor().execute(request(sourceFolder, targetFolder, false, false));

			Assertions.assertThat(response.status()).isEqualTo(ExecutionStatus.ERROR.name());
			Assertions.assertThat(response.rejected()).isTrue();
			Assertions.assertThat(response.message()).contains("Organization rejected");

			verify(organizationPlanner, never()).preview(any());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		} finally {
			releaseLock.countDown();
		}

		Assertions.assertThatCode(lockThread::join).doesNotThrowAnyException();
	}

	@Test
	void executeShouldSkipItemsAlreadyInSamePath() {
		Path sourceFolder = tempDir.resolve("source");
		Path targetFolder = tempDir.resolve("target");

		OrganizationItem samePath = item(1L, sourceFolder.resolve("photo.jpg"), sourceFolder.resolve("photo.jpg"), true,
				false);

		when(executionRepository.save(any())).thenAnswer(invocation -> {
			Execution execution = invocation.getArgument(0);
			execution.setId(1L);
			return execution;
		});

		when(organizationPlanner.preview(any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 1, 0, 100, 0, 0, 0), List.of(samePath)));

		var response = executor().execute(request(sourceFolder, targetFolder, false, false));

		Assertions.assertThat(response.plannedMoves()).isZero();
		Assertions.assertThat(response.skipped()).isEqualTo(1);
		Assertions.assertThat(response.status()).isEqualTo(ExecutionStatus.FINISHED.name());
	}

	@Test
	void executeShouldMoveFileAndUpdateDatabase() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = tempDir.resolve("target");
		Path source = Files.writeString(sourceFolder.resolve("photo.jpg"), "content");
		Path target = targetFolder.resolve("202405/09/CAMERA/IMAGENS/photo.jpg");

		OrganizationItem item = item(1L, source, target, false, false);

		CatalogFile catalogFile = CatalogFile.builder().id(1L).fileName("photo.jpg").modifiedAt(LocalDateTime.now())
				.build();

		CatalogFileLocation location = CatalogFileLocation.builder().catalogFile(catalogFile)
				.currentPath(source.toString())
				.build();

		catalogFile.setLocation(location);

		when(executionRepository.save(any())).thenAnswer(invocation -> {
			Execution execution = invocation.getArgument(0);
			execution.setId(1L);
			return execution;
		});

		when(organizationPlanner.preview(any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());
		when(catalogFileRepository.findById(1L)).thenReturn(Optional.of(catalogFile));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(any(), any()))
				.thenReturn(Optional.of(location));

		var response = executor().execute(request(sourceFolder, targetFolder, false, false));

		Assertions.assertThat(response.moved()).isEqualTo(1);
		Assertions.assertThat(response.status()).isEqualTo(ExecutionStatus.FINISHED.name());
		Assertions.assertThat(Files.exists(target)).isTrue();
		Assertions.assertThat(catalogFile.getFileName()).isEqualTo("photo.jpg");

		verify(catalogFileRepository, times(1)).findById(1L);
	}

	@Test
	void executeShouldRemoveTheEmptiedSourceSubfoldersUpToTheSourceRoot() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path subFolder = Files.createDirectories(sourceFolder.resolve("old/2018"));
		Path targetFolder = tempDir.resolve("target");
		Path source = Files.writeString(subFolder.resolve("photo.jpg"), "content");
		Path target = targetFolder.resolve("202405/09/CAMERA/IMAGENS/photo.jpg");

		OrganizationItem item = item(1L, source, target, false, false);

		CatalogFile catalogFile = CatalogFile.builder().id(1L).fileName("photo.jpg").modifiedAt(LocalDateTime.now())
				.build();

		CatalogFileLocation location = CatalogFileLocation.builder().catalogFile(catalogFile)
				.currentPath(source.toString())
				.build();

		catalogFile.setLocation(location);

		when(executionRepository.save(any())).thenAnswer(invocation -> {
			Execution execution = invocation.getArgument(0);
			execution.setId(1L);
			return execution;
		});

		when(organizationPlanner.preview(any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());
		when(catalogFileRepository.findById(1L)).thenReturn(Optional.of(catalogFile));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(any(), any()))
				.thenReturn(Optional.of(location));

		var response = executor().execute(request(sourceFolder, targetFolder, false, false));

		Assertions.assertThat(response.moved()).isEqualTo(1);
		Assertions.assertThat(Files.exists(target)).isTrue();
		// The now-empty source subfolders are removed, walking up to (but never
		// removing) the source root.
		Assertions.assertThat(Files.exists(subFolder)).isFalse();
		Assertions.assertThat(Files.exists(sourceFolder.resolve("old"))).isFalse();
		Assertions.assertThat(Files.exists(sourceFolder)).isTrue();
	}

	@Test
	void executeShouldSkipSymbolicLinkSourceWithoutTouchingItsTarget() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = tempDir.resolve("target");
		Path realFile = Files.writeString(sourceFolder.resolve("real.jpg"), "content");
		Path linkSource;

		try {
			linkSource = Files.createSymbolicLink(sourceFolder.resolve("link.jpg"), realFile);
		} catch (IOException | UnsupportedOperationException | SecurityException exception) {
			Assumptions.abort("Symbolic links not supported: " + exception.getMessage());
			return;
		}

		Path target = targetFolder.resolve("202405/09/CAMERA/IMAGENS/link.jpg");

		OrganizationItem item = item(1L, linkSource, target, false, false);

		when(executionRepository.save(any())).thenAnswer(invocation -> {
			Execution execution = invocation.getArgument(0);
			execution.setId(1L);
			return execution;
		});
		when(organizationPlanner.preview(any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());

		var response = executor().execute(request(sourceFolder, targetFolder, false, false));

		Assertions.assertThat(response.moved()).isZero();
		Assertions.assertThat(response.skipped()).isEqualTo(1);
		// The real file the link points to is never moved or removed.
		Assertions.assertThat(Files.exists(realFile)).isTrue();
		Assertions.assertThat(Files.exists(target)).isFalse();

		verify(catalogFileRepository, never()).findById(any());
	}

	@Test
	void executeShouldSkipLnkShortcutSourceWithoutCatalogLookup() throws Exception {
		// Portable sibling of the symlink test: a .lnk file is refused by extension, so
		// this runs everywhere (no symlink privilege needed) and pins down that a
		// refused
		// shortcut is recorded WITHOUT a catalog lookup - the exact contract the
		// CI-only
		// symlink case relies on.
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = tempDir.resolve("target");
		Path shortcut = Files.writeString(sourceFolder.resolve("link.lnk"), "shortcut");
		Path target = targetFolder.resolve("202405/09/CAMERA/IMAGENS/link.lnk");

		OrganizationItem item = item(1L, shortcut, target, false, false);

		ArgumentCaptor<Movement> movementCaptor = ArgumentCaptor.forClass(Movement.class);

		when(executionRepository.save(any())).thenAnswer(invocation -> {
			Execution execution = invocation.getArgument(0);
			execution.setId(1L);
			return execution;
		});
		when(organizationPlanner.preview(any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());

		var response = executor().execute(request(sourceFolder, targetFolder, false, false));

		Assertions.assertThat(response.moved()).isZero();
		Assertions.assertThat(response.skipped()).isEqualTo(1);
		Assertions.assertThat(Files.exists(shortcut)).isTrue();
		Assertions.assertThat(Files.exists(target)).isFalse();

		verify(catalogFileRepository, never()).findById(any());
		verify(movementRepository).save(movementCaptor.capture());

		Assertions.assertThat(movementCaptor.getValue().getStatus()).isEqualTo(MovementStatus.SKIPPED);
		Assertions.assertThat(movementCaptor.getValue().getReason()).isEqualTo(MovementReason.SOURCE_NOT_PHYSICAL);
		Assertions.assertThat(movementCaptor.getValue().getCatalogFile()).isNull();
	}

	@Test
	void executeShouldNotMoveFileWhenDatabaseStateCannotBePrepared() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = tempDir.resolve("target");
		Path source = Files.writeString(sourceFolder.resolve("photo.jpg"), "content");
		Path target = targetFolder.resolve("photo.jpg");

		OrganizationItem item = item(1L, source, target, false, false);

		CatalogFile catalogFile = CatalogFile.builder().id(1L).fileName("photo.jpg").build();

		ArgumentCaptor<Movement> movementCaptor = ArgumentCaptor.forClass(Movement.class);

		when(executionRepository.save(any())).thenAnswer(invocation -> {
			Execution execution = invocation.getArgument(0);
			execution.setId(1L);
			return execution;
		});

		when(organizationPlanner.preview(any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());
		when(catalogFileRepository.findById(1L)).thenReturn(Optional.of(catalogFile));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(any(), any()))
				.thenReturn(Optional.empty());

		var response = executor().execute(request(sourceFolder, targetFolder, false, false));

		Assertions.assertThat(response.moved()).isZero();
		Assertions.assertThat(response.errors()).isEqualTo(1);
		Assertions.assertThat(Files.exists(source)).isTrue();
		Assertions.assertThat(Files.exists(target)).isFalse();

		verify(movementRepository).save(movementCaptor.capture());

		Assertions.assertThat(movementCaptor.getValue().getStatus()).isEqualTo(MovementStatus.ERROR);
		Assertions.assertThat(movementCaptor.getValue().getReason()).isEqualTo(MovementReason.IO_ERROR);
	}

	@Test
	void executeShouldRollbackPhysicalMoveWhenDatabaseSaveFailsAfterMove() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = tempDir.resolve("target");
		Path source = Files.writeString(sourceFolder.resolve("photo.jpg"), "content");
		Path target = targetFolder.resolve("photo.jpg");

		OrganizationItem item = item(1L, source, target, false, false);

		CatalogFile catalogFile = CatalogFile.builder().id(1L).fileName("photo.jpg").modifiedAt(LocalDateTime.now())
				.build();

		CatalogFileLocation location = CatalogFileLocation.builder().catalogFile(catalogFile)
				.currentPath(source.toString())
				.build();

		ArgumentCaptor<Movement> movementCaptor = ArgumentCaptor.forClass(Movement.class);

		catalogFile.setLocation(location);

		when(executionRepository.save(any())).thenAnswer(invocation -> {
			Execution execution = invocation.getArgument(0);
			execution.setId(1L);
			return execution;
		});

		when(organizationPlanner.preview(any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());
		when(catalogFileRepository.findById(1L)).thenReturn(Optional.of(catalogFile));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(any(), any()))
				.thenReturn(Optional.of(location));
		doThrow(new IllegalStateException("database down")).when(catalogFileRepository).save(catalogFile);

		var response = executor().execute(request(sourceFolder, targetFolder, false, false));

		Assertions.assertThat(response.moved()).isZero();
		Assertions.assertThat(response.errors()).isEqualTo(1);
		Assertions.assertThat(Files.exists(source)).isTrue();
		Assertions.assertThat(Files.exists(target)).isFalse();

		verify(movementRepository).save(movementCaptor.capture());

		Assertions.assertThat(movementCaptor.getValue().getStatus()).isEqualTo(MovementStatus.ERROR);
		Assertions.assertThat(movementCaptor.getValue().getReason()).isEqualTo(MovementReason.DATABASE_UPDATE_FAILED);
		Assertions.assertThat(recordedError()).contains("Physical rollback succeeded");
	}

	@Test
	void executeShouldReportRollbackFailureWhenSourceParentDisappearsAfterMove() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = tempDir.resolve("target");
		Path source = Files.writeString(sourceFolder.resolve("photo.jpg"), "content");
		Path target = targetFolder.resolve("photo.jpg");

		OrganizationItem item = item(1L, source, target, false, false);

		CatalogFile catalogFile = CatalogFile.builder().id(1L).fileName("photo.jpg").build();

		CatalogFileLocation location = CatalogFileLocation.builder().catalogFile(catalogFile)
				.currentPath(source.toString())
				.build();

		ArgumentCaptor<Movement> movementCaptor = ArgumentCaptor.forClass(Movement.class);

		when(executionRepository.save(any())).thenAnswer(invocation -> {
			Execution execution = invocation.getArgument(0);
			execution.setId(1L);
			return execution;
		});
		when(organizationPlanner.preview(any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());
		when(catalogFileRepository.findById(1L)).thenReturn(Optional.of(catalogFile));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(any(), any()))
				.thenReturn(Optional.of(location));
		doAnswer(_ -> {
			Files.deleteIfExists(sourceFolder);

			throw new IllegalStateException("database down");
		}).when(catalogFileRepository).save(catalogFile);

		var response = executor().execute(request(sourceFolder, targetFolder, false, false));

		Assertions.assertThat(response.status()).isEqualTo(ExecutionStatus.FINISHED_WITH_ERRORS.name());
		Assertions.assertThat(response.moved()).isZero();
		Assertions.assertThat(response.errors()).isEqualTo(1);

		verify(movementRepository).save(movementCaptor.capture());

		Assertions.assertThat(recordedError()).contains("Physical rollback failed");
		Assertions.assertThat(Files.exists(target)).isTrue();
	}

	@Test
	void executeShouldRollbackAndMarkIntegrityFailureWhenPostMoveCheckFails() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = tempDir.resolve("target");
		Path source = Files.writeString(sourceFolder.resolve("photo.jpg"), "content");
		Path target = targetFolder.resolve("photo.jpg");

		OrganizationItem item = item(1L, source, target, false, false);

		CatalogFile catalogFile = CatalogFile.builder().id(1L).fileName("photo.jpg").modifiedAt(LocalDateTime.now())
				.build();

		CatalogFileLocation location = CatalogFileLocation.builder().catalogFile(catalogFile)
				.currentPath(source.toString())
				.build();

		ArgumentCaptor<Movement> movementCaptor = ArgumentCaptor.forClass(Movement.class);

		catalogFile.setLocation(location);

		when(executionRepository.save(any())).thenAnswer(invocation -> {
			Execution execution = invocation.getArgument(0);
			execution.setId(1L);
			return execution;
		});

		when(organizationPlanner.preview(any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());
		when(catalogFileRepository.findById(1L)).thenReturn(Optional.of(catalogFile));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(any(), any()))
				.thenReturn(Optional.of(location));

		OrganizationMoveVerifier verifier = mock(OrganizationMoveVerifier.class);

		when(verifier.capture(any())).thenReturn(new MoveBaseline(7L, "deadbeef"));
		doThrow(new MoveIntegrityException("target SHA-256 does not match source SHA-256 (data corruption on move)."))
				.when(verifier).verify(any(), any(), any());

		var response = executor(verifier).execute(request(sourceFolder, targetFolder, false, false));

		Assertions.assertThat(response.moved()).isZero();
		Assertions.assertThat(response.errors()).isEqualTo(1);
		Assertions.assertThat(response.status()).isEqualTo(ExecutionStatus.FINISHED_WITH_ERRORS.name());
		// Physical rollback restores the source and removes the corrupt target.
		Assertions.assertThat(Files.exists(source)).isTrue();
		Assertions.assertThat(Files.exists(target)).isFalse();

		// Catalog is never updated when integrity fails.
		verify(catalogFileRepository, never()).save(any());
		verify(movementRepository).save(movementCaptor.capture());

		Assertions.assertThat(movementCaptor.getValue().getStatus()).isEqualTo(MovementStatus.ERROR);
		Assertions.assertThat(movementCaptor.getValue().getReason()).isEqualTo(MovementReason.INTEGRITY_CHECK_FAILED);
		Assertions.assertThat(recordedError()).contains("integrity check failed", "Physical rollback succeeded");
	}

	@Test
	void executeShouldSkipDuplicateTargetAndMissingSource() {
		Path sourceFolder = tempDir.resolve("source");
		Path targetFolder = tempDir.resolve("target");

		OrganizationItem duplicate = item(1L, sourceFolder.resolve("a.jpg"), targetFolder.resolve("a.jpg"), false,
				true);
		OrganizationItem missing = item(2L, sourceFolder.resolve("missing.jpg"), targetFolder.resolve("missing.jpg"),
				false, false);

		when(executionRepository.save(any())).thenAnswer(invocation -> {
			Execution execution = invocation.getArgument(0);
			execution.setId(1L);
			return execution;
		});

		when(organizationPlanner.preview(any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(2, 2, 0, 0, 2, 100, 0, 0, 1), List.of(duplicate, missing)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());
		when(catalogFileRepository.findById(any())).thenReturn(Optional.empty());

		var response = executor().execute(request(sourceFolder, targetFolder, true, false));

		Assertions.assertThat(response.skipped()).isEqualTo(1);
		Assertions.assertThat(response.errors()).isEqualTo(1);
		Assertions.assertThat(response.status()).isEqualTo(ExecutionStatus.FINISHED_WITH_ERRORS.name());
	}

	@Test
	void executeShouldSkipAlreadyMovedFileWhenSourceIsMissingAndTargetIsRegistered() throws Exception {
		Path sourceFolder = tempDir.resolve("source");
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));
		Path source = sourceFolder.resolve("photo.jpg");
		Path target = Files.writeString(targetFolder.resolve("photo.jpg"), "content");

		OrganizationItem item = item(1L, source, target, false, false);

		CatalogFile catalogFile = CatalogFile.builder().id(1L).fileName("photo.jpg").fileKey(target.toString()).build();

		ArgumentCaptor<Movement> movementCaptor = ArgumentCaptor.forClass(Movement.class);

		when(executionRepository.save(any())).thenAnswer(invocation -> {
			Execution execution = invocation.getArgument(0);
			execution.setId(1L);
			return execution;
		});

		when(organizationPlanner.preview(any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.of(catalogFile));

		var response = executor().execute(request(sourceFolder, targetFolder, false, false));

		Assertions.assertThat(response.moved()).isZero();
		Assertions.assertThat(response.skipped()).isEqualTo(1);
		Assertions.assertThat(response.errors()).isZero();
		Assertions.assertThat(response.status()).isEqualTo(ExecutionStatus.FINISHED.name());
		Assertions.assertThat(Files.exists(target)).isTrue();

		verify(movementRepository).save(movementCaptor.capture());

		Assertions.assertThat(movementCaptor.getValue().getStatus()).isEqualTo(MovementStatus.SKIPPED);
		Assertions.assertThat(movementCaptor.getValue().getReason()).isEqualTo(MovementReason.ALREADY_MOVED);

		verify(catalogFileRepository, never()).findById(1L);
	}

	@SuppressWarnings("unchecked")
	@Test
	void executeShouldSkipWhenTargetAlreadyExistsInDatabaseOrFilesystem() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));
		Path sourceA = Files.writeString(sourceFolder.resolve("a.jpg"), "a");
		Path sourceB = Files.writeString(sourceFolder.resolve("b.jpg"), "b");
		Path targetA = targetFolder.resolve("a.jpg");
		Path targetB = Files.writeString(targetFolder.resolve("b.jpg"), "existing");

		OrganizationItem registeredTarget = item(1L, sourceA, targetA, false, false);
		OrganizationItem existingTarget = item(2L, sourceB, targetB, false, false);

		when(executionRepository.save(any())).thenAnswer(invocation -> {
			Execution execution = invocation.getArgument(0);
			execution.setId(1L);
			return execution;
		});

		when(organizationPlanner.preview(any())).thenReturn(new OrganizationPlan(sourceFolder.toString(),
				targetFolder.toString(), OrganizationLayout.DEFAULT, false,
				new OrganizationSummary(2, 2, 0, 0, 2, 100, 0, 0, 0), List.of(registeredTarget, existingTarget)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.of(CatalogFile.builder().id(99L).build()),
				Optional.empty());
		when(catalogFileRepository.findById(any())).thenReturn(Optional.empty());

		var response = executor().execute(request(sourceFolder, targetFolder, true, false));

		Assertions.assertThat(response.moved()).isZero();
		Assertions.assertThat(response.skipped()).isEqualTo(2);
		Assertions.assertThat(response.errors()).isZero();
		Assertions.assertThat(Files.exists(sourceA)).isTrue();
		Assertions.assertThat(Files.exists(sourceB)).isTrue();
	}

	@Test
	void executeShouldOverwriteExistingTargetAndFallbackToLocationList() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));
		Path source = Files.writeString(sourceFolder.resolve("photo.jpg"), "new");
		Path target = Files.writeString(targetFolder.resolve("photo.jpg"), "old");

		OrganizationItem item = item(1L, source, target, false, false);

		CatalogFile catalogFile = CatalogFile.builder().id(1L).modifiedAt(LocalDateTime.of(2024, Month.JANUARY, 1, 0,
				0))
				.build();

		CatalogFileLocation location = CatalogFileLocation.builder().catalogFile(catalogFile)
				.currentPath(source.toString())
				.build();

		catalogFile.setLocation(location);

		when(executionRepository.save(any())).thenAnswer(invocation -> {
			Execution execution = invocation.getArgument(0);
			execution.setId(1L);
			return execution;
		});

		when(organizationPlanner.preview(any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());
		when(catalogFileRepository.findById(1L)).thenReturn(Optional.of(catalogFile));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(any(), any()))
				.thenReturn(Optional.empty());

		var response = executor().execute(request(sourceFolder, targetFolder, true, true));

		Assertions.assertThat(response.moved()).isEqualTo(1);
		Assertions.assertThat(Files.readString(target)).isEqualTo("new");
		Assertions.assertThat(location.getCurrentPath()).contains("photo.jpg");
	}

	@Test
	void executeShouldMarkErrorWhenPreviewFailsOrMovementRecordingFails() {
		Path sourceFolder = tempDir.resolve("source");
		Path targetFolder = tempDir.resolve("target");

		OrganizationItem item = item(1L, sourceFolder.resolve("missing.jpg"), targetFolder.resolve("missing.jpg"),
				false, false);

		when(executionRepository.save(any())).thenAnswer(invocation -> {
			Execution execution = invocation.getArgument(0);
			execution.setId(1L);
			return execution;
		});

		when(organizationPlanner.preview(any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
		doThrow(new IllegalStateException("movement failed")).when(movementRepository).save(any());

		var movementFailure = executor().execute(request(sourceFolder, targetFolder, true, false));

		Assertions.assertThat(movementFailure.errors()).isEqualTo(1);

		when(organizationPlanner.preview(any())).thenThrow(new IllegalStateException("preview failed"));

		var previewFailure = executor().execute(request(sourceFolder, targetFolder, true, false));

		Assertions.assertThat(previewFailure.status()).isEqualTo(ExecutionStatus.ERROR.name());
		Assertions.assertThat(previewFailure.message()).contains("preview failed");
	}

	@Test
	void executeWithProvidedExecutionShouldReportTotalAndProgressWhenProgressServiceIsPresent() {
		Execution execution = Execution.builder().id(42L).build();

		Path sourceFolder = tempDir.resolve("source");
		Path targetFolder = tempDir.resolve("target");

		OrganizationItem samePath = item(1L, sourceFolder.resolve("a.jpg"), sourceFolder.resolve("a.jpg"), true, false);
		OrganizationItem missing = item(2L, sourceFolder.resolve("missing.jpg"), targetFolder.resolve("missing.jpg"),
				false, false);

		when(organizationPlanner.preview(any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(2, 2, 0, 1, 1, 100, 0, 0, 0), List.of(samePath, missing)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());
		when(catalogFileRepository.findById(any())).thenReturn(Optional.empty());

		var response = executorWithProgress().execute(request(sourceFolder, targetFolder, true, false), execution);

		Assertions.assertThat(response.executionId()).isEqualTo(UuidV7.fromLegacy(42L));

		verify(executionProgressService).updateTotal(execution, 2);
		verify(executionProgressService).updateStatus(execution, ExecutionStatus.PROCESSING_FILES,
				ExecutionStepType.PROCESSING_STARTED, ExecutionMessages.processingFiles());
		verify(executionProgressService, atLeastOnce()).updateProgress(eq(execution), anyInt(), anyInt(), anyInt(),
				anyInt(), anyString());
	}

	@Test
	void executeShouldReportProgressAtCadenceOf500ItemsWhenProgressServiceIsPresent() {
		Execution execution = Execution.builder().id(43L).build();

		Path sourceFolder = tempDir.resolve("source");
		Path targetFolder = tempDir.resolve("target");

		List<OrganizationItem> items = new ArrayList<>();

		for (long i = 1; i <= 500; i++) {
			items.add(item(i, sourceFolder.resolve("file" + i + ".jpg"), sourceFolder.resolve("file" + i + ".jpg"),
					true, false));
		}

		when(organizationPlanner.preview(any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(500, 500, 0, 500, 0, 0, 0, 0, 0), items));

		executorWithProgress().execute(request(sourceFolder, targetFolder, true, false), execution);

		verify(executionProgressService).updateProgress(eq(execution), eq(1), anyInt(), anyInt(), anyInt(),
				anyString());
		verify(executionProgressService).updateProgress(eq(execution), eq(500), anyInt(), anyInt(), anyInt(),
				anyString());
		verify(executionProgressService, times(2)).updateProgress(eq(execution), anyInt(), anyInt(), anyInt(), anyInt(),
				anyString());
		verify(executionProgressService).updateLiveProgress(eq(execution), eq(25), anyInt(), anyInt(), anyInt(), any());
	}

	@Test
	void executeShouldStopAndMarkCancelledWhenCancellationIsRequestedMidLoop() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = tempDir.resolve("target");

		OrganizationItem first = item(1L, sourceFolder.resolve("a.jpg"), sourceFolder.resolve("a.jpg"), true, false);
		OrganizationItem second = item(2L, sourceFolder.resolve("b.jpg"), targetFolder.resolve("b.jpg"), false, false);

		when(executionRepository.save(any())).thenAnswer(invocation -> {
			Execution execution = invocation.getArgument(0);
			execution.setId(1L);
			return execution;
		});

		when(organizationPlanner.preview(any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(2, 2, 0, 0, 1, 100, 0, 0, 0), List.of(first, second)));

		doAnswer(_ -> {
			executionCancellationService.requestCancellation(1L);

			return null;
		}).when(executionProgressService).updateProgress(any(), eq(1), anyInt(), anyInt(), anyInt(), anyString());

		OrganizationExecutor executor = executor();

		var response = executor.execute(request(sourceFolder, targetFolder, false, false));

		Assertions.assertThat(response.status()).isEqualTo(ExecutionStatus.CANCELLED.name());
		Assertions.assertThat(response.skipped()).isEqualTo(1);

		verify(catalogFileRepository, never()).findByFileKey(any());

		// execute() unregisters in its finally block once it stops, cancelled or not,
		// so this
		// confirms cleanup happened instead of leaving a stale entry behind.
		Assertions.assertThat(executionCancellationService.isCancelled(1L)).isFalse();
	}

	@Test
	void dryRunShouldSimulateMovesWithoutTouchingDiskOrDatabase() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = tempDir.resolve("target");
		Path source = Files.writeString(sourceFolder.resolve("photo.jpg"), "content");
		Path target = targetFolder.resolve("202405/09/CAMERA/IMAGENS/photo.jpg");

		OrganizationItem item = item(1L, source, target, false, false);

		CatalogFile catalogFile = CatalogFile.builder().id(1L).fileName("photo.jpg").modifiedAt(LocalDateTime.now())
				.build();

		CatalogFileLocation location = CatalogFileLocation.builder().catalogFile(catalogFile)
				.currentPath(source.toString())
				.build();

		OrganizationMoveVerifier verifier = mock(OrganizationMoveVerifier.class);
		OrganizationMovePersistence persistence = mock(OrganizationMovePersistence.class);

		catalogFile.setLocation(location);

		when(executionRepository.save(any())).thenAnswer(invocation -> {
			Execution execution = invocation.getArgument(0);
			execution.setId(1L);
			return execution;
		});
		when(organizationPlanner.preview(any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());
		when(catalogFileRepository.findById(1L)).thenReturn(Optional.of(catalogFile));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(any(), any()))
				.thenReturn(Optional.of(location));

		var response = executor(verifier, persistence).execute(dryRunRequest(sourceFolder, targetFolder, false));

		// The item WOULD move, and the counters are reported exactly as a real execute.
		Assertions.assertThat(response.moved()).isEqualTo(1);
		Assertions.assertThat(response.errors()).isZero();
		Assertions.assertThat(response.status()).isEqualTo(ExecutionStatus.FINISHED.name());
		Assertions.assertThat(response.message()).contains("Preview finished", "would move=1");
		// Zero mutation: nothing on disk moved, nothing written, no persistence, no
		// verify.
		Assertions.assertThat(Files.exists(source)).isTrue();
		Assertions.assertThat(Files.exists(target)).isFalse();

		verify(catalogFileRepository, never()).save(any());
		verify(catalogFileLocationRepository, never()).save(any());
		verify(movementRepository, never()).save(any());
		verify(verifier, never()).capture(any());
		verify(verifier, never()).verify(any(), any(), any());
		Mockito.verifyNoInteractions(persistence);
	}

	@Test
	void dryRunShouldReportTheSameCountsAsARealExecute() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = tempDir.resolve("target");
		Path source = Files.writeString(sourceFolder.resolve("photo.jpg"), "content");
		Path target = targetFolder.resolve("202405/09/CAMERA/IMAGENS/photo.jpg");

		OrganizationItem item = item(1L, source, target, false, false);

		CatalogFile catalogFile = CatalogFile.builder().id(1L).fileName("photo.jpg").modifiedAt(LocalDateTime.now())
				.build();

		CatalogFileLocation location = CatalogFileLocation.builder().catalogFile(catalogFile)
				.currentPath(source.toString())
				.build();

		catalogFile.setLocation(location);

		when(executionRepository.save(any())).thenAnswer(invocation -> {
			Execution execution = invocation.getArgument(0);
			execution.setId(1L);
			return execution;
		});
		when(organizationPlanner.preview(any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());
		when(catalogFileRepository.findById(1L)).thenReturn(Optional.of(catalogFile));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(any(), any()))
				.thenReturn(Optional.of(location));

		var dry = executor().execute(dryRunRequest(sourceFolder, targetFolder, false));

		Assertions.assertThat(Files.exists(source)).isTrue();

		var real = executor().execute(request(sourceFolder, targetFolder, false, false));

		Assertions.assertThat(dry.moved()).isEqualTo(real.moved()).isEqualTo(1);
		Assertions.assertThat(dry.skipped()).isEqualTo(real.skipped());
		Assertions.assertThat(dry.errors()).isEqualTo(real.errors());
		Assertions.assertThat(Files.exists(target)).isTrue();
	}

	@Test
	void dryRunShouldRejectConflictingPlanExactlyLikeExecute() {
		Path sourceFolder = tempDir.resolve("source");
		Path targetFolder = tempDir.resolve("target");

		OrganizationMovePersistence persistence = mock(OrganizationMovePersistence.class);

		when(executionRepository.save(any())).thenAnswer(invocation -> {
			Execution execution = invocation.getArgument(0);
			execution.setId(1L);
			return execution;
		});
		when(organizationPlanner.preview(any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(2, 2, 0, 0, 2, 100, 1, 1, 0), List.of()));

		var response = executor(new OrganizationMoveVerifier(new FileHashService()), persistence)
				.execute(dryRunRequest(sourceFolder, targetFolder, false));

		Assertions.assertThat(response.rejected()).isTrue();
		Assertions.assertThat(response.status()).isEqualTo(ExecutionStatus.REJECTED.name());
		Assertions.assertThat(response.errors()).isZero();

		verify(movementRepository, never()).save(any());
		Mockito.verifyNoInteractions(persistence);
	}

	@Test
	void dryRunWithAllowConflictsShouldSimulateSkipsWithoutWriting() {
		Path sourceFolder = tempDir.resolve("source");
		Path targetFolder = tempDir.resolve("target");

		OrganizationItem duplicate = item(1L, sourceFolder.resolve("a.jpg"), targetFolder.resolve("a.jpg"), false,
				true);

		OrganizationMovePersistence persistence = mock(OrganizationMovePersistence.class);

		when(executionRepository.save(any())).thenAnswer(invocation -> {
			Execution execution = invocation.getArgument(0);
			execution.setId(1L);
			return execution;
		});
		when(organizationPlanner.preview(any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 1, 0, 1), List.of(duplicate)));

		var response = executor(new OrganizationMoveVerifier(new FileHashService()), persistence)
				.execute(dryRunRequest(sourceFolder, targetFolder, true));

		Assertions.assertThat(response.moved()).isZero();
		Assertions.assertThat(response.skipped()).isEqualTo(1);
		Assertions.assertThat(response.status()).isEqualTo(ExecutionStatus.FINISHED.name());

		verify(movementRepository, never()).save(any());
		Mockito.verifyNoInteractions(persistence);
	}

	private OrganizationExecutor executor() {
		return executor(new OrganizationMoveVerifier(new FileHashService()));
	}

	private OrganizationExecutor executor(OrganizationMoveVerifier verifier, OrganizationMovePersistence persistence) {
		return new OrganizationExecutor(organizationPlanner, executionRepository, catalogFileRepository,
				catalogFileLocationRepository, movementLog(), operationLockService, executionProgressService,
				executionCancellationService, new SecureFileMove(verifier, pathRegistry), persistence,
				new OrganizationPlanStore(), new EmptyDirectoryCleaner(), Clock.systemDefaultZone());
	}

	/**
	 * The real log over the mocked repository: the movement stubs keep working and
	 * the error it records on a failure is exercised instead of stubbed away.
	 */
	private final ExecutionErrorService executionErrorService = mock(ExecutionErrorService.class);

	/**
	 * The reason a move failed lives with every other per-file failure now, so the
	 * assertions read it from there instead of from the movement row.
	 */
	private String recordedError() {
		ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);

		verify(executionErrorService).save(any(), eq(ExecutionErrorType.MOVE_ERROR), message.capture(), any());

		return message.getValue();
	}

	private OrganizationMovementLog movementLog() {
		return new OrganizationMovementLog(movementRepository, catalogFileRepository, executionErrorService);
	}

	private OrganizationExecuteRequest dryRunRequest(Path source, Path target, boolean allowConflicts) {
		return new OrganizationExecuteRequest(source.toString(), target.toString(), true, OrganizationLayout.DEFAULT,
				100, false, null, true, null, null, null, null, allowConflicts, false, LocationSubdivision.NONE, null,
				LocationFallbackMode.IGNORE, true);
	}

	private OrganizationExecutor executor(OrganizationMoveVerifier verifier) {
		return new OrganizationExecutor(organizationPlanner, executionRepository, catalogFileRepository,
				catalogFileLocationRepository, movementLog(), operationLockService, executionProgressService,
				executionCancellationService, new SecureFileMove(verifier, pathRegistry),
				new OrganizationMovePersistence(catalogFileRepository, catalogFileLocationRepository,
						movementRepository,
						Clock.systemDefaultZone()),
				new OrganizationPlanStore(), new EmptyDirectoryCleaner(), Clock.systemDefaultZone());
	}

	private OrganizationExecutor executorWithProgress() {
		return executor();
	}

	private Thread holdLock(Path path, CountDownLatch lockAcquired, CountDownLatch releaseLock) {
		Thread thread = new Thread(() -> {
			try (var _ = operationLockService.acquire(ExecutionType.INVENTORY, path)) {
				lockAcquired.countDown();
				releaseLock.await();
			} catch (InterruptedException _) {
				Thread.currentThread().interrupt();
			}
		});

		thread.start();

		return thread;
	}

	private OrganizationExecuteRequest request(Path source, Path target, boolean allowConflicts,
			boolean overwriteExisting) {
		return new OrganizationExecuteRequest(source.toString(), target.toString(), true, OrganizationLayout.DEFAULT,
				100, null, null, null, null, null, null, null, allowConflicts, overwriteExisting);
	}

	private OrganizationItem item(Long id, Path source, Path target, boolean samePath, boolean duplicateTarget) {
		return new OrganizationItem(id, source.getFileName().toString(), source.toString(), target.toString(), "202405",
				"09", "MEDIA", "CAMERA", "IMAGENS", "CAMERA", "FILE_NAME", 100L, samePath, false, false,
				duplicateTarget, duplicateTarget, duplicateTarget ? "DUPLICATE_TARGET" : null);
	}
}