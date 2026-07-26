package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateDeletionResult;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLock;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.MoveIntegrityException;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationMoveVerifier;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureFileMove;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MoveBaseline;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantineIntakeService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

class DuplicateDeletionServiceTest {

	private final CatalogFileRepository catalogFileRepository = mock(CatalogFileRepository.class);
	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
	private final AppSettingService appSettingService = mock(AppSettingService.class);
	private final DuplicateDeletionPersistence persistence = mock(DuplicateDeletionPersistence.class);
	private final SimilarityCaches similarityCaches = mock(SimilarityCaches.class);
	private final OperationLockService operationLockService = mock(OperationLockService.class);
	private final OperationLock operationLock = mock(OperationLock.class);
	private final QuarantineIntakeService quarantineIntakeService = new QuarantineIntakeService(persistence,
			new SecureFileMove(new OrganizationMoveVerifier(new FileHashService())), appSettingService);
	private final DuplicateDeletionService service = new DuplicateDeletionService(catalogFileRepository,
			executionRepository, quarantineIntakeService, similarityCaches, operationLockService,
			Clock.systemDefaultZone());

	DuplicateDeletionServiceTest() {
		when(operationLockService.acquire(eq(ExecutionType.DEDUP_DELETE), any(Path[].class))).thenReturn(operationLock);
	}

	@Test
	void refusesWhenQuarantineFolderIsNotConfigured() {
		when(appSettingService.stringValue(SettingsConstants.TRASH_FOLDER, "")).thenReturn("");

		DuplicateDeletionResult result = service.delete(List.of(UUID.randomUUID()));

		Assertions.assertThat(result.configured()).isFalse();

		verify(catalogFileRepository, never()).findByPublicIdIn(any());
		verify(executionRepository, never()).save(any());
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

		DuplicateDeletionResult result = service.delete(List.of(UUID.randomUUID()));

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
		when(appSettingService.stringValue(SettingsConstants.TRASH_FOLDER, "")).thenReturn("/tmp/trash");

		DuplicateDeletionResult result = service.delete(List.of());

		Assertions.assertThat(result.configured()).isTrue();
		Assertions.assertThat(result.moved()).isZero();

		verify(executionRepository, never()).save(any());
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

		DuplicateDeletionResult result = service.delete(List.of(UUID.randomUUID()));

		Assertions.assertThat(result.moved()).isZero();
		Assertions.assertThat(result.errors()).isEqualTo(1);
		Assertions.assertThat(Files.exists(original)).isTrue();
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
				executionRepository,
				new QuarantineIntakeService(persistence, new SecureFileMove(verifier), appSettingService),
				similarityCaches, operationLockService, Clock.systemDefaultZone());

		configureTrash(trash);

		stubExecution();

		CatalogFile file = stubFile(13L, original, "c.jpg");

		when(catalogFileRepository.findByPublicIdIn(any())).thenReturn(List.of(file));

		DuplicateDeletionResult result = integrityFailingService.delete(List.of(UUID.randomUUID()));

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

		DuplicateDeletionResult result = service.delete(List.of(UUID.randomUUID()));

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

		DuplicateDeletionResult result = service.delete(List.of(UUID.randomUUID(), UUID.randomUUID()));

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

		DuplicateDeletionResult result = service.delete(List.of(UUID.randomUUID()));

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

		DuplicateDeletionResult result = service.delete(List.of(file.getPublicId()));

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

		DuplicateDeletionResult result = service.delete(List.of(file.getPublicId()));

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

		List<int[]> updates = new ArrayList<>();

		service.delete(List.of(UUID.randomUUID(), UUID.randomUUID()),
				(processed, total) -> updates.add(new int[] { processed, total }));

		Assertions.assertThat(updates).hasSize(3);
		Assertions.assertThat(updates.get(0)).containsExactly(0, 2);
		Assertions.assertThat(updates.get(1)).containsExactly(1, 2);
		Assertions.assertThat(updates.get(2)).containsExactly(2, 2);
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

		DuplicateDeletionResult result = service.delete(List.of(publicId));

		Assertions.assertThat(result.moved()).isZero();
		Assertions.assertThat(result.errors()).isEqualTo(1);
		Assertions.assertThat(Files.exists(original)).isTrue();

		verify(executionRepository, never()).save(any());
		verify(persistence, never()).persistQuarantine(any(), any(), any(), any(), any());
	}

	private void configureTrash(Path trash) {
		when(appSettingService.stringValue(SettingsConstants.TRASH_FOLDER, "")).thenReturn(trash.toString());
	}

	private Execution stubExecution() {
		Execution saved = mock(Execution.class);

		when(saved.getId()).thenReturn(1L);
		when(saved.getPublicId()).thenReturn(UUID.randomUUID());
		when(executionRepository.save(any())).thenReturn(saved);
		when(executionRepository.findById(1L)).thenReturn(Optional.of(saved));

		return saved;
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
}