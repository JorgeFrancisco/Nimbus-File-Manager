package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;

class DuplicateDeletionPersistenceTest {

	private final CatalogFileRepository catalogFileRepository = mock(CatalogFileRepository.class);
	private final CatalogFileLocationRepository catalogFileLocationRepository =
			mock(CatalogFileLocationRepository.class);
	private final MovementRepository movementRepository = mock(MovementRepository.class);
	private final DuplicateDeletionPersistence persistence = new DuplicateDeletionPersistence(catalogFileRepository,
			catalogFileLocationRepository, movementRepository, Clock.systemDefaultZone());

	private final Path original = Path.of("D:", "lib", "a.jpg");
	private final Path quarantine = Path.of("D:", "trash", "exec-1", "10__a.jpg");

	@Test
	void quarantineRepointsCatalogMarksDeletedAndRecordsMovement() {
		CatalogFileLocation location = mock(CatalogFileLocation.class);
		CatalogFile file = mock(CatalogFile.class);

		when(file.getLocation()).thenReturn(location);

		persistence.persistQuarantine(mock(Execution.class), file, original, quarantine);

		verify(file).markDeleted();
		verify(file).setFileName("10__a.jpg");
		verify(location).setCurrentPath(any());
		verify(catalogFileRepository).save(file);
		verify(catalogFileLocationRepository).save(location);
		verify(movementRepository).save(any(Movement.class));
	}

	@Test
	void restoreRepointsCatalogMarksActiveAndRecordsMovement() {
		CatalogFileLocation location = mock(CatalogFileLocation.class);
		CatalogFile file = mock(CatalogFile.class);

		when(file.getLocation()).thenReturn(location);

		persistence.persistRestore(mock(Execution.class), file, quarantine, original);

		verify(file).markActive();
		verify(file).setFileName("a.jpg");
		verify(catalogFileRepository).save(file);
		verify(movementRepository).save(any(Movement.class));
	}

	@Test
	void applyRestoreReloadsManagedEntitiesRepointsAndMarksMovementUndone() {
		CatalogFileLocation location = mock(CatalogFileLocation.class);
		CatalogFile managed = mock(CatalogFile.class);

		when(managed.getLocation()).thenReturn(location);

		UUID filePublicId = UUID.randomUUID();

		CatalogFile detached = mock(CatalogFile.class);

		when(detached.getPublicId()).thenReturn(filePublicId);

		Movement movement = mock(Movement.class);

		when(movement.getCatalogFile()).thenReturn(detached);
		when(movement.getId()).thenReturn(7L);

		when(catalogFileRepository.findByPublicIdIn(List.of(filePublicId))).thenReturn(List.of(managed));
		when(movementRepository.findById(7L)).thenReturn(Optional.of(movement));

		persistence.applyRestore(movement, original);

		verify(managed).markActive();
		verify(managed).setFileName("a.jpg");
		verify(catalogFileRepository).save(managed);
		verify(movement).setStatus(MovementStatus.UNDONE);
		verify(movement).setUndoneAt(any());
		verify(movementRepository).save(movement);
	}
}