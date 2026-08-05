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

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.execution.application.NoCancellations;
import br.com.jorgemelo.nimbusfilemanager.execution.application.GrantingOperationLocks;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.EligibilityAnnouncer;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OwnershipLostException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationFallbackMode;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationSubdivision;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MoveBaseline;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationItem;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPlan;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationSummary;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.shared.application.InMemorySelfWrittenPaths;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

@ExtendWith(MockitoExtension.class)
class OrganizationExecutorTest {

	private final SelfWrittenPathRegistry pathRegistry = new SelfWrittenPathRegistry(new InMemorySelfWrittenPaths(),
			Clock.systemDefaultZone());

	@TempDir
	Path tempDir;

	@Mock
	private EligibilityAnnouncer eligibilityAnnouncer;

	@Mock
	private OrganizationPlanner organizationPlanner;

	@Mock
	private CatalogFileRepository catalogFileRepository;

	@Mock
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Mock
	private MovementRepository movementRepository;

	@Mock
	private ExecutionProgressService executionProgressService;

	private OperationLockService operationLockService = GrantingOperationLocks.granting();

	private final ExecutionCancellationService executionCancellationService = NoCancellations.none();

	/**
	 * The taking the executor is running under is the one the planner plans under -
	 * the same object, not one rebuilt from the execution's id. Planning used to be
	 * handed nothing, which is why it was silent on screen and uncancellable.
	 */
	@Test
	void thePlannerPlansUnderTheVeryTakingTheExecutionIsRunningUnder() {
		when(organizationPlanner.preview(any(), any())).thenReturn(emptyPlan());

		ExecutionOwnership taking = owning();

		executor().execute(request(tempDir.resolve("source"), tempDir.resolve("target"), false, false),
			Execution.builder().id(1L).build(), taking);

		ArgumentCaptor<ExecutionOwnership> handedOver = ArgumentCaptor.forClass(ExecutionOwnership.class);

		verify(organizationPlanner).preview(any(), handedOver.capture());

		Assertions.assertThat(handedOver.getValue()).isSameAs(taking);
	}

	/**
	 * The total has one owner. The planner counts candidates it examined and the
	 * executor counts the work the run will actually do, so letting both write it
	 * would move the denominator under the bar half way through the same execution.
	 */
	@Test
	void onlyTheExecutorSaysHowMuchWorkThereIs() {
		when(organizationPlanner.preview(any(), any())).thenReturn(new OrganizationPlan("source", "target",
			OrganizationLayout.DEFAULT, false, new OrganizationSummary(2, 2, 0, 0, 0, 100, 0, 0, 0),
			List.of()));

		ExecutionOwnership taking = owning();

		executor().execute(request(tempDir.resolve("source"), tempDir.resolve("target"), false, false),
			Execution.builder().id(1L).build(), taking);

		verify(executionProgressService).updateTotal(taking, 0);
	}

	/**
	 * Handing the taking over does not turn one run into two: the plan is still
	 * built once, and nothing queues a second execution to build it again.
	 */
	@Test
	void handingTheTakingOverDoesNotPlanTwice() {
		when(organizationPlanner.preview(any(), any())).thenReturn(emptyPlan());

		execute(executor(), request(tempDir.resolve("source"), tempDir.resolve("target"), false, false));

		verify(organizationPlanner, times(1)).preview(any(), any());
	}

	private OrganizationPlan emptyPlan() {
		return new OrganizationPlan("source", "target", OrganizationLayout.DEFAULT, false,
			new OrganizationSummary(0, 0, 0, 0, 0, 100, 0, 0, 0), List.of());
	}

	@Test
	void executeShouldRejectPlanWithConflictsWhenNotAllowed() {
		when(organizationPlanner.preview(any(), any())).thenReturn(new OrganizationPlan("source", "target",
				OrganizationLayout.DEFAULT, false, new OrganizationSummary(2, 2, 0, 0, 2, 100, 1, 1, 0), List.of()));

		var response = execute(executor(), request(tempDir.resolve("source"), tempDir.resolve("target"), false, false));

		Assertions.assertThat(response.rejected()).isTrue();
		Assertions.assertThat(response.status()).isEqualTo(ExecutionStatus.REJECTED.name());
		Assertions.assertThat(response.errors()).isZero();
	}

	@Test
	void executeShouldRejectWhenSourcePathIsAlreadyLocked() {
		Path sourceFolder = tempDir.resolve("source");
		Path targetFolder = tempDir.resolve("target");

		operationLockService = GrantingOperationLocks.refusing();

		var response = execute(executor(), request(sourceFolder, targetFolder, false, false));

		Assertions.assertThat(response.status()).isEqualTo(ExecutionStatus.ERROR.name());
		Assertions.assertThat(response.rejected()).isTrue();
		Assertions.assertThat(response.message()).contains("Organization rejected");

		verify(organizationPlanner, never()).preview(any(), any());
	}

	@Test
	void executeShouldSkipItemsAlreadyInSamePath() {
		Path sourceFolder = tempDir.resolve("source");
		Path targetFolder = tempDir.resolve("target");

		OrganizationItem samePath = item(1L, sourceFolder.resolve("photo.jpg"), sourceFolder.resolve("photo.jpg"), true,
				false);

		when(organizationPlanner.preview(any(), any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 1, 0, 100, 0, 0, 0), List.of(samePath)));

		var response = execute(executor(), request(sourceFolder, targetFolder, false, false));

		Assertions.assertThat(response.plannedMoves()).isZero();
		Assertions.assertThat(response.skipped()).isEqualTo(1);
		Assertions.assertThat(response.status()).isEqualTo(ExecutionStatus.FINISHED.name());
	}

	/**
	 * A run that moved files says so once, and only when the exclusion list has
	 * something to say about the two trees. Announcing per file would ask for the
	 * same regroup a hundred thousand times over a library the first request
	 * already described.
	 */
	@Test
	void aRunThatCrossedAnExclusionBoundaryAsksForOneRegroup() throws Exception {
		when(eligibilityAnnouncer.repointCanChangeEligibility(any(), any())).thenReturn(true);

		moveOneFile();

		verify(eligibilityAnnouncer).announce("organization move");
	}

	/**
	 * The same run over a library with nothing hidden: the folder of every file
	 * changed and the answer would not, so nothing is asked for.
	 */
	@Test
	void aRunThatNoExclusionCaresAboutAsksForNothing() throws Exception {
		moveOneFile();

		verify(eligibilityAnnouncer, never()).announce(any());
	}

	/**
	 * The plan says a file would move; nothing did. A preview that asked for a
	 * regroup would be a preview with a side effect.
	 */
	@Test
	void aDryRunAsksForNothingHoweverManyMovesItPlanned() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = tempDir.resolve("target");
		Path source = Files.writeString(sourceFolder.resolve("photo.jpg"), "content");

		stubPlanFor(sourceFolder, targetFolder, source);

		executor().execute(dryRunRequest(sourceFolder, targetFolder, false), Execution.builder().id(1L).build(),
				owning());

		verify(eligibilityAnnouncer, never()).announce(any());
	}

	/**
	 * One real move, arranged the way
	 * {@code executeShouldMoveFileAndUpdateDatabase} arranges it.
	 */
	private void moveOneFile() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = tempDir.resolve("target");
		Path source = Files.writeString(sourceFolder.resolve("photo.jpg"), "content");

		CatalogFile catalogFile = CatalogFile.builder().id(1L).fileName("photo.jpg").modifiedAt(LocalDateTime.now())
				.build();

		CatalogFileLocation location = CatalogFileLocation.builder().catalogFile(catalogFile)
				.currentPath(source.toString()).build();

		catalogFile.setLocation(location);

		stubPlanFor(sourceFolder, targetFolder, source);

		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());
		when(catalogFileRepository.findById(1L)).thenReturn(Optional.of(catalogFile));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(any(), any()))
				.thenReturn(Optional.of(location));

		Assertions.assertThat(execute(executor(), request(sourceFolder, targetFolder, false, false)).moved())
				.isEqualTo(1);
	}

	private void stubPlanFor(Path sourceFolder, Path targetFolder, Path source) {
		OrganizationItem item = item(1L, source, targetFolder.resolve("202405/09/CAMERA/IMAGENS/photo.jpg"), false,
				false);

		when(organizationPlanner.preview(any(), any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
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
				.currentPath(source.toString()).build();

		catalogFile.setLocation(location);

		when(organizationPlanner.preview(any(), any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());
		when(catalogFileRepository.findById(1L)).thenReturn(Optional.of(catalogFile));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(any(), any()))
				.thenReturn(Optional.of(location));

		var response = execute(executor(), request(sourceFolder, targetFolder, false, false));

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
				.currentPath(source.toString()).build();

		catalogFile.setLocation(location);

		when(organizationPlanner.preview(any(), any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());
		when(catalogFileRepository.findById(1L)).thenReturn(Optional.of(catalogFile));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(any(), any()))
				.thenReturn(Optional.of(location));

		var response = execute(executor(), request(sourceFolder, targetFolder, false, false));

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

		when(organizationPlanner.preview(any(), any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());

		var response = execute(executor(), request(sourceFolder, targetFolder, false, false));

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

		when(organizationPlanner.preview(any(), any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());

		var response = execute(executor(), request(sourceFolder, targetFolder, false, false));

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

		when(organizationPlanner.preview(any(), any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());
		when(catalogFileRepository.findById(1L)).thenReturn(Optional.of(catalogFile));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(any(), any()))
				.thenReturn(Optional.empty());

		var response = execute(executor(), request(sourceFolder, targetFolder, false, false));

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
				.currentPath(source.toString()).build();

		ArgumentCaptor<Movement> movementCaptor = ArgumentCaptor.forClass(Movement.class);

		catalogFile.setLocation(location);

		when(organizationPlanner.preview(any(), any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());
		when(catalogFileRepository.findById(1L)).thenReturn(Optional.of(catalogFile));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(any(), any()))
				.thenReturn(Optional.of(location));
		doThrow(new IllegalStateException("database down")).when(catalogFileRepository).save(catalogFile);

		var response = execute(executor(), request(sourceFolder, targetFolder, false, false));

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
				.currentPath(source.toString()).build();

		ArgumentCaptor<Movement> movementCaptor = ArgumentCaptor.forClass(Movement.class);

		when(organizationPlanner.preview(any(), any())).thenReturn(
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

		var response = execute(executor(), request(sourceFolder, targetFolder, false, false));

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
				.currentPath(source.toString()).build();

		ArgumentCaptor<Movement> movementCaptor = ArgumentCaptor.forClass(Movement.class);

		catalogFile.setLocation(location);

		when(organizationPlanner.preview(any(), any())).thenReturn(
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

		var response = execute(executor(verifier), request(sourceFolder, targetFolder, false, false));

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

		when(organizationPlanner.preview(any(), any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(2, 2, 0, 0, 2, 100, 0, 0, 1), List.of(duplicate, missing)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());
		when(catalogFileRepository.findById(any())).thenReturn(Optional.empty());

		var response = execute(executor(), request(sourceFolder, targetFolder, true, false));

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

		when(organizationPlanner.preview(any(), any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.of(catalogFile));

		var response = execute(executor(), request(sourceFolder, targetFolder, false, false));

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

		when(organizationPlanner.preview(any(), any())).thenReturn(new OrganizationPlan(sourceFolder.toString(),
				targetFolder.toString(), OrganizationLayout.DEFAULT, false,
				new OrganizationSummary(2, 2, 0, 0, 2, 100, 0, 0, 0), List.of(registeredTarget, existingTarget)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.of(CatalogFile.builder().id(99L).build()),
				Optional.empty());
		when(catalogFileRepository.findById(any())).thenReturn(Optional.empty());

		var response = execute(executor(), request(sourceFolder, targetFolder, true, false));

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

		CatalogFile catalogFile = CatalogFile.builder().id(1L)
				.modifiedAt(LocalDateTime.of(2024, Month.JANUARY, 1, 0, 0)).build();

		CatalogFileLocation location = CatalogFileLocation.builder().catalogFile(catalogFile)
				.currentPath(source.toString()).build();

		catalogFile.setLocation(location);

		when(organizationPlanner.preview(any(), any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());
		when(catalogFileRepository.findById(1L)).thenReturn(Optional.of(catalogFile));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(any(), any()))
				.thenReturn(Optional.empty());

		var response = execute(executor(), request(sourceFolder, targetFolder, true, true));

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

		when(organizationPlanner.preview(any(), any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
		doThrow(new IllegalStateException("movement failed")).when(movementRepository).save(any());

		var movementFailure = execute(executor(), request(sourceFolder, targetFolder, true, false));

		Assertions.assertThat(movementFailure.errors()).isEqualTo(1);

		when(organizationPlanner.preview(any(), any())).thenThrow(new IllegalStateException("preview failed"));

		var previewFailure = execute(executor(), request(sourceFolder, targetFolder, true, false));

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

		when(organizationPlanner.preview(any(), any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(2, 2, 0, 1, 1, 100, 0, 0, 0), List.of(samePath, missing)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());
		when(catalogFileRepository.findById(any())).thenReturn(Optional.empty());

		var response = executorWithProgress().execute(request(sourceFolder, targetFolder, true, false), execution,
				owning());

		Assertions.assertThat(response.executionId()).isEqualTo(UuidV7.fromLegacy(42L));

		verify(executionProgressService).updateTotal(ownership, 2);
		verify(executionProgressService).updatePhase(ownership, ExecutionPhase.PROCESSING,
				ExecutionStepType.PROCESSING_STARTED, ExecutionMessages.processingFiles());
		verify(executionProgressService, atLeastOnce()).updateProgress(eq(ownership), anyInt(), anyInt(), anyInt(),
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

		when(organizationPlanner.preview(any(), any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(500, 500, 0, 500, 0, 0, 0, 0, 0), items));

		executorWithProgress().execute(request(sourceFolder, targetFolder, true, false), execution, owning());

		verify(executionProgressService).updateProgress(eq(ownership), eq(1), anyInt(), anyInt(), anyInt(),
				anyString());
		verify(executionProgressService).updateProgress(eq(ownership), eq(500), anyInt(), anyInt(), anyInt(),
				anyString());
		verify(executionProgressService, times(2)).updateProgress(eq(ownership), anyInt(), anyInt(), anyInt(), anyInt(),
				anyString());
		verify(executionProgressService).updateLiveProgress(eq(ownership), eq(25), anyInt(), anyInt(), anyInt(), any());
	}

	@Test
	void executeShouldStopAndMarkCancelledWhenCancellationIsRequestedMidLoop() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = tempDir.resolve("target");

		OrganizationItem first = item(1L, sourceFolder.resolve("a.jpg"), sourceFolder.resolve("a.jpg"), true, false);
		OrganizationItem second = item(2L, sourceFolder.resolve("b.jpg"), targetFolder.resolve("b.jpg"), false, false);

		when(organizationPlanner.preview(any(), any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(2, 2, 0, 0, 1, 100, 0, 0, 0), List.of(first, second)));

		doAnswer(_ -> {
			executionCancellationService.requestCancellation(1L);

			return null;
		}).when(executionProgressService).updateProgress(any(), eq(1), anyInt(), anyInt(), anyInt(), anyString());

		OrganizationExecutor executor = executor();

		var response = execute(executor, request(sourceFolder, targetFolder, false, false));

		Assertions.assertThat(response.status()).isEqualTo(ExecutionStatus.CANCELLED.name());
		Assertions.assertThat(response.skipped()).isEqualTo(1);

		verify(catalogFileRepository, never()).findByFileKey(any());

		// execute() unregisters in its finally block once it stops, cancelled or not,
		// so this confirms cleanup happened instead of leaving a stale entry behind.
		// The cancellation itself stays on the row - a request that survives the
		// thread is the whole point of persisting it.
		Assertions.assertThat(executionCancellationService.isCancelled(1L)).isTrue();
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
				.currentPath(source.toString()).build();

		OrganizationMoveVerifier verifier = mock(OrganizationMoveVerifier.class);
		OrganizationMovePersistence persistence = mock(OrganizationMovePersistence.class);

		catalogFile.setLocation(location);

		when(organizationPlanner.preview(any(), any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());
		when(catalogFileRepository.findById(1L)).thenReturn(Optional.of(catalogFile));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(any(), any()))
				.thenReturn(Optional.of(location));

		var response = execute(executor(verifier, persistence), dryRunRequest(sourceFolder, targetFolder, false));

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
				.currentPath(source.toString()).build();

		catalogFile.setLocation(location);

		when(organizationPlanner.preview(any(), any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of(item)));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());
		when(catalogFileRepository.findById(1L)).thenReturn(Optional.of(catalogFile));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(any(), any()))
				.thenReturn(Optional.of(location));

		var dry = execute(executor(), dryRunRequest(sourceFolder, targetFolder, false));

		Assertions.assertThat(Files.exists(source)).isTrue();

		var real = execute(executor(), request(sourceFolder, targetFolder, false, false));

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

		when(organizationPlanner.preview(any(), any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(2, 2, 0, 0, 2, 100, 1, 1, 0), List.of()));

		var response = execute(executor(new OrganizationMoveVerifier(new FileHashService()), persistence),
				dryRunRequest(sourceFolder, targetFolder, false));

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

		when(organizationPlanner.preview(any(), any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 1, 0, 1), List.of(duplicate)));

		var response = execute(executor(new OrganizationMoveVerifier(new FileHashService()), persistence),
				dryRunRequest(sourceFolder, targetFolder, true));

		Assertions.assertThat(response.moved()).isZero();
		Assertions.assertThat(response.skipped()).isEqualTo(1);
		Assertions.assertThat(response.status()).isEqualTo(ExecutionStatus.FINISHED.name());

		verify(movementRepository, never()).save(any());
		Mockito.verifyNoInteractions(persistence);
	}

	/**
	 * The checkpoint is what stops a process writing to a library it may already
	 * have lost the right to write to. It closes the commit, not the computing:
	 * what has already moved was moved while the locks were held and verified byte
	 * for byte, so it stays where it is and the run ends interrupted rather than
	 * failed.
	 */
	@Test
	void stopsBeforeTheNextFileWhenTheLocksUnderItAreGone() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));
		Path firstSource = Files.writeString(sourceFolder.resolve("a.jpg"), "a");
		Path secondSource = Files.writeString(sourceFolder.resolve("b.jpg"), "b");
		Path firstTarget = targetFolder.resolve("a.jpg");
		Path secondTarget = targetFolder.resolve("b.jpg");

		CatalogFile catalogFile = CatalogFile.builder().id(1L).build();

		CatalogFileLocation location = CatalogFileLocation.builder().catalogFile(catalogFile)
				.currentPath(firstSource.toString()).build();

		catalogFile.setLocation(location);

		when(organizationPlanner.preview(any(), any())).thenReturn(new OrganizationPlan(sourceFolder.toString(),
				targetFolder.toString(), OrganizationLayout.DEFAULT, false,
				new OrganizationSummary(2, 2, 0, 0, 2, 100, 0, 0, 0),
				List.of(item(1L, firstSource, firstTarget, false, false),
						item(2L, secondSource, secondTarget, false, false))));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());
		when(catalogFileRepository.findById(1L)).thenReturn(Optional.of(catalogFile));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(any(), any()))
				.thenReturn(Optional.of(location));

		ExecutionOwnership lost = mock(ExecutionOwnership.class);

		Mockito.doNothing().doThrow(new OwnershipLostException("the session that held the locks is gone"))
				.when(lost).assertMayGoOnWorking();

		var response = executor().execute(request(sourceFolder, targetFolder, false, false),
				Execution.builder().id(1L).build(), lost);

		Assertions.assertThat(response.status()).isEqualTo(ExecutionStatus.INTERRUPTED.name());
		Assertions.assertThat(response.moved()).isEqualTo(1);
		Assertions.assertThat(Files.exists(firstTarget)).isTrue();
		Assertions.assertThat(Files.exists(secondTarget)).isFalse();
		Assertions.assertThat(Files.exists(secondSource)).isTrue();
	}

	/**
	 * A plan naming a file the catalog no longer has is the catalog and the plan
	 * disagreeing, which is a defect rather than a file to skip: the item is
	 * counted as an error and the run carries on with the rest.
	 */
	@Test
	void countsAnErrorForAPlannedFileTheCatalogNoLongerHas() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = tempDir.resolve("target");
		Path source = Files.writeString(sourceFolder.resolve("photo.jpg"), "content");
		Path target = targetFolder.resolve("202405/09/CAMERA/IMAGENS/photo.jpg");

		when(organizationPlanner.preview(any(), any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0),
						List.of(item(1L, source, target, false, false))));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());
		when(catalogFileRepository.findById(1L)).thenReturn(Optional.empty());

		var response = execute(executor(), request(sourceFolder, targetFolder, false, false));

		Assertions.assertThat(response.errors()).isEqualTo(1);
		Assertions.assertThat(response.moved()).isZero();
		Assertions.assertThat(Files.exists(source)).isTrue();
		Assertions.assertThat(Files.exists(target)).isFalse();
	}

	/**
	 * The preview is the one caller with nothing to own, and it calls the executor
	 * without an ownership at all. It stayed in the application because it writes
	 * nothing - so the call has to work, and has to leave the disk exactly as it
	 * found it.
	 */
	@Test
	void runsAPreviewWithNoOwnershipToCheck() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = tempDir.resolve("target");
		Path source = Files.writeString(sourceFolder.resolve("photo.jpg"), "content");
		Path target = targetFolder.resolve("202405/09/CAMERA/IMAGENS/photo.jpg");

		CatalogFile catalogFile = CatalogFile.builder().id(1L).fileName("photo.jpg").modifiedAt(LocalDateTime.now())
				.build();

		CatalogFileLocation location = CatalogFileLocation.builder().catalogFile(catalogFile)
				.currentPath(source.toString()).build();

		catalogFile.setLocation(location);

		when(organizationPlanner.preview(any(), any())).thenReturn(
				new OrganizationPlan(sourceFolder.toString(), targetFolder.toString(), OrganizationLayout.DEFAULT,
						false, new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0),
						List.of(item(1L, source, target, false, false))));
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());
		when(catalogFileRepository.findById(1L)).thenReturn(Optional.of(catalogFile));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(any(), any()))
				.thenReturn(Optional.of(location));

		var response = executor().execute(dryRunRequest(sourceFolder, targetFolder, false),
				Execution.builder().id(1L).build(), owning());

		Assertions.assertThat(response.moved()).isEqualTo(1);
		Assertions.assertThat(response.status()).isEqualTo(ExecutionStatus.FINISHED.name());
		Assertions.assertThat(Files.exists(source)).isTrue();
		Assertions.assertThat(Files.exists(target)).isFalse();
	}

	/**
	 * The executor no longer opens a row of its own: a worker hands it the
	 * execution it claimed together with the ownership of the paths it locked, and
	 * these tests hand it the same two things. The ownership answers that the locks
	 * are still held, so the checkpoint between files passes and what each test
	 * asserts is the behaviour it is about rather than an interrupted run.
	 */
	private OrganizationExecuteResponse execute(OrganizationExecutor executor, OrganizationExecuteRequest request) {
		return executor.execute(request, Execution.builder().id(1L).build(), owning());
	}

	/**
	 * The taking every write about the row is made under. A real one rather than a
	 * stub, so a write refused for having lost its turn is refused here too.
	 */
	private final ExecutionOwnership ownership = Takings.owning(1L);

	private ExecutionOwnership owning() {
		return ownership;
	}

	private OrganizationExecutor executor() {
		return executor(new OrganizationMoveVerifier(new FileHashService()));
	}

	private OrganizationExecutor executor(OrganizationMoveVerifier verifier, OrganizationMovePersistence persistence) {
		return new OrganizationExecutor(organizationPlanner, catalogFileRepository, catalogFileLocationRepository,
				movementLog(), operationLockService, executionProgressService, executionCancellationService,
				new SecureLibraryFiles(new SecureFileMove(verifier, pathRegistry), pathRegistry), persistence,
				new EmptyDirectoryCleaner(libraryFiles()), eligibilityAnnouncer, Clock.systemDefaultZone());
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
		return new OrganizationExecutor(organizationPlanner, catalogFileRepository, catalogFileLocationRepository,
				movementLog(), operationLockService, executionProgressService, executionCancellationService,
				new SecureLibraryFiles(new SecureFileMove(verifier, pathRegistry), pathRegistry),
				new OrganizationMovePersistence(catalogFileRepository, catalogFileLocationRepository,
						movementRepository, Clock.systemDefaultZone()),
				new EmptyDirectoryCleaner(libraryFiles()), eligibilityAnnouncer, Clock.systemDefaultZone());
	}

	private OrganizationExecutor executorWithProgress() {
		return executor();
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

	/**
	 * The real port over a real registry: these assertions are about a file that
	 * has to actually leave the disk, and about the announcement that keeps the
	 * watcher from re-inventorying it.
	 */
	private static SecureLibraryFiles libraryFiles() {
		SelfWrittenPathRegistry registry = new SelfWrittenPathRegistry(new InMemorySelfWrittenPaths(),
				Clock.systemUTC());

		return new SecureLibraryFiles(new SecureFileMove(new OrganizationMoveVerifier(new FileHashService()),
				registry), registry);
	}
}