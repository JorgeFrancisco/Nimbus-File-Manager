package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Month;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

@ExtendWith(MockitoExtension.class)
class OrganizationMovePersistenceTest {

	@TempDir
	Path tempDir;

	@Mock
	private CatalogFileRepository catalogFileRepository;

	@Mock
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Mock
	private MovementRepository movementRepository;

	@Test
	void persistShouldUpdateCatalogAndRecordMovedMovementInOneUnit() throws Exception {
		OrganizationMovePersistence persistence = persistence();

		Execution execution = Execution.builder().id(5L).build();

		CatalogFile catalogFile = CatalogFile.builder().id(1L).fileName("old.jpg")
				.modifiedAt(LocalDateTime.of(2020, Month.JANUARY, 1, 0, 0)).build();

		CatalogFileLocation location = CatalogFileLocation.builder().catalogFile(catalogFile)
				.currentPath("D:/src/old.jpg").build();

		Path source = tempDir.resolve("old.jpg");
		Path targetDir = Files.createDirectory(tempDir.resolve("dest"));
		Path target = Files.writeString(targetDir.resolve("new.jpg"), "content");

		ArgumentCaptor<Movement> movementCaptor = ArgumentCaptor.forClass(Movement.class);

		CatalogFile result = persistence.persistSuccessfulMove(execution, catalogFile, location, source, target);

		Assertions.assertThat(result).isSameAs(catalogFile);
		Assertions.assertThat(catalogFile.getFileKey()).isEqualTo(PathUtils.normalize(target));
		Assertions.assertThat(catalogFile.getFileName()).isEqualTo("new.jpg");
		Assertions.assertThat(location.getCurrentPath()).isEqualTo(PathUtils.normalize(target));
		Assertions.assertThat(location.getCurrentFolder()).isEqualTo(PathUtils.normalize(targetDir));

		InOrder inOrder = Mockito.inOrder(catalogFileLocationRepository, catalogFileRepository, movementRepository);

		inOrder.verify(catalogFileLocationRepository).save(location);
		inOrder.verify(catalogFileRepository).save(catalogFile);
		inOrder.verify(movementRepository).save(any(Movement.class));

		verify(movementRepository).save(movementCaptor.capture());

		Movement movement = movementCaptor.getValue();

		Assertions.assertThat(movement.getStatus()).isEqualTo(MovementStatus.MOVED);
		Assertions.assertThat(movement.getReason()).isNull();
		Assertions.assertThat(movement.getExecution()).isSameAs(execution);
		Assertions.assertThat(movement.getCatalogFile()).isSameAs(catalogFile);
		Assertions.assertThat(movement.getSourcePath()).isEqualTo(PathUtils.normalize(source));
		Assertions.assertThat(movement.getTargetPath()).isEqualTo(PathUtils.normalize(target));
	}

	@Test
	void persistShouldFailWhenTargetHasNoParentDirectory() {
		OrganizationMovePersistence persistence = persistence();

		Execution execution = Execution.builder().id(5L).build();

		CatalogFile catalogFile = CatalogFile.builder().id(1L).fileName("old.jpg").build();

		CatalogFileLocation location = CatalogFileLocation.builder().catalogFile(catalogFile).build();

		Path oldPath = Path.of("old.jpg");
		Path newPath = Path.of("new.jpg");

		Assertions
				.assertThatThrownBy(
						() -> persistence.persistSuccessfulMove(execution, catalogFile, location, oldPath, newPath))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("parent directory");
	}

	private OrganizationMovePersistence persistence() {
		return new OrganizationMovePersistence(catalogFileRepository, catalogFileLocationRepository, movementRepository,
				Clock.systemDefaultZone());
	}
}