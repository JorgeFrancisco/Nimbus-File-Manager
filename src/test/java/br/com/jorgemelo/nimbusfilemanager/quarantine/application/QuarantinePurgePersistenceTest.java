package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.MovementPurgeResult;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;

class QuarantinePurgePersistenceTest {

	private final MovementRepository movementRepository = mock(MovementRepository.class);
	private final CatalogFileRepository catalogFileRepository = mock(CatalogFileRepository.class);
	private final QuarantinePurgePersistence persistence = new QuarantinePurgePersistence(movementRepository,
			catalogFileRepository);

	@Test
	void deletesMovementAndReportsRemovedWithCatalogFileId() {
		CatalogFile catalogFile = mock(CatalogFile.class);

		when(catalogFile.getId()).thenReturn(9L);

		Movement movement = mock(Movement.class);

		when(movement.getStatus()).thenReturn(MovementStatus.MOVED);
		when(movement.getReason()).thenReturn(MovementReason.DUPLICATE_QUARANTINED);
		when(movement.getCatalogFile()).thenReturn(catalogFile);
		when(movementRepository.findById(1L)).thenReturn(Optional.of(movement));

		MovementPurgeResult result = persistence.deleteMovement(1L);

		Assertions.assertThat(result.removed()).isTrue();
		Assertions.assertThat(result.catalogFileId()).isEqualTo(9L);

		verify(movementRepository).delete(movement);
	}

	@Test
	void reportsNotRemovedForAMovementThatWasAlreadyRestored() {
		Movement movement = mock(Movement.class);

		when(movement.getStatus()).thenReturn(MovementStatus.UNDONE);
		when(movementRepository.findById(1L)).thenReturn(Optional.of(movement));

		MovementPurgeResult result = persistence.deleteMovement(1L);

		Assertions.assertThat(result.removed()).isFalse();
		Assertions.assertThat(result.catalogFileId()).isNull();

		verify(movementRepository, never()).delete(movement);
	}

	@Test
	void deletesOrphanDeletedCatalogFile() {
		CatalogFile catalogFile = mock(CatalogFile.class);

		when(catalogFile.isDeleted()).thenReturn(true);
		when(movementRepository.countByCatalogFileIdAndStatusAndReasonIn(9L, MovementStatus.MOVED,
				QuarantineConstants.QUARANTINED_REASONS)).thenReturn(0L);
		when(catalogFileRepository.findById(9L)).thenReturn(Optional.of(catalogFile));

		boolean deleted = persistence.deleteCatalogFileIfOrphan(9L);

		Assertions.assertThat(deleted).isTrue();

		verify(catalogFileRepository).delete(catalogFile);
	}

	@Test
	void keepsCatalogFileStillReferencedByAnotherMovement() {
		when(movementRepository.countByCatalogFileIdAndStatusAndReasonIn(9L, MovementStatus.MOVED,
				QuarantineConstants.QUARANTINED_REASONS)).thenReturn(2L);

		boolean deleted = persistence.deleteCatalogFileIfOrphan(9L);

		Assertions.assertThat(deleted).isFalse();

		verify(catalogFileRepository, never()).delete(ArgumentMatchers.any());
	}

	@Test
	void keepsCatalogFileThatIsNotDeleted() {
		CatalogFile catalogFile = mock(CatalogFile.class);

		when(catalogFile.isDeleted()).thenReturn(false);
		when(movementRepository.countByCatalogFileIdAndStatusAndReasonIn(9L, MovementStatus.MOVED,
				QuarantineConstants.QUARANTINED_REASONS)).thenReturn(0L);
		when(catalogFileRepository.findById(9L)).thenReturn(Optional.of(catalogFile));

		boolean deleted = persistence.deleteCatalogFileIfOrphan(9L);

		Assertions.assertThat(deleted).isFalse();

		verify(catalogFileRepository, never()).delete(catalogFile);
	}
}