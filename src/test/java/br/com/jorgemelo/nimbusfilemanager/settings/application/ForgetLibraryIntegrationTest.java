package br.com.jorgemelo.nimbusfilemanager.settings.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.application.catalog.CatalogMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import jakarta.persistence.EntityManager;

/**
 * What forgetting a library does, and - the part worth a real database - what it
 * does not.
 *
 * <p>
 * It removes catalogued files of one library and leaves everything else: the
 * other library's catalog, the movement history of the files it removed, and
 * every byte on disk. The last one is the reason this test exists at all: a
 * cleanup that grew into a deletion would be the worst defect this product could
 * have, and no amount of reading the code proves it did not.
 */
class ForgetLibraryIntegrationTest extends SharedPostgresIntegrationTest {

	@Autowired
	private CatalogMutations catalogMutations;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private MovementRepository movementRepository;

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	void onlyTheForgottenLibraryLeavesTheCatalog(@TempDir Path root) throws IOException {
		Path forgotten = Files.createDirectories(root.resolve("A"));
		Path kept = Files.createDirectories(root.resolve("B"));

		CatalogFile inside = persist(forgotten.resolve("photo.jpg"));
		CatalogFile deeper = persist(Files.createDirectories(forgotten.resolve("2026")).resolve("nested.jpg"));
		CatalogFile elsewhere = persist(kept.resolve("photo.jpg"));

		entityManager.clear();

		Assertions.assertThat(catalogMutations.forgetLibrary(forgotten.toString())).isEqualTo(2);

		entityManager.clear();

		Assertions.assertThat(catalogFileRepository.findById(inside.getId())).isEmpty();
		Assertions.assertThat(catalogFileRepository.findById(deeper.getId())).isEmpty();
		Assertions.assertThat(catalogFileRepository.findById(elsewhere.getId())).isPresent();
	}

	/**
	 * The one that matters most. Forgetting is a catalog operation: the library it
	 * forgot is still on disk, whole, and re-pointing at it would catalogue it
	 * again.
	 */
	@Test
	void notOneFileOfTheUsersIsTouched(@TempDir Path root) throws IOException {
		Path forgotten = Files.createDirectories(root.resolve("A"));
		Path photo = Files.writeString(forgotten.resolve("photo.jpg"), "the user's bytes");
		Path nested = Files.writeString(Files.createDirectories(forgotten.resolve("2026")).resolve("nested.jpg"),
				"more of them");

		persist(photo);
		persist(nested);

		entityManager.clear();

		catalogMutations.forgetLibrary(forgotten.toString());

		Assertions.assertThat(photo).exists().hasContent("the user's bytes");
		Assertions.assertThat(nested).exists().hasContent("more of them");
		Assertions.assertThat(forgotten).isDirectory();
	}

	/**
	 * History survives the catalog. A movement says something happened to a file,
	 * and that remains true after the file stops being part of the collection - the
	 * foreign key detaches the row instead of deleting it.
	 */
	@Test
	void theHistoryOfWhatWasMovedSurvives(@TempDir Path root) throws IOException {
		Path forgotten = Files.createDirectories(root.resolve("A"));

		CatalogFile file = persist(forgotten.resolve("photo.jpg"));

		// A movement belongs to the run that made it, and that run is history of its
		// own: forgetting a library must not take it either.
		Execution execution = executionRepository.saveAndFlush(Execution.builder()
				.executionType(ExecutionType.ORGANIZATION).status(ExecutionStatus.FINISHED)
				.publicId(UUID.randomUUID()).sourcePath(forgotten.toString()).recursive(true).executeFlag(true)
				.build());

		Movement movement = movementRepository.saveAndFlush(Movement.builder().execution(execution).catalogFile(file)
				.sourcePath(forgotten.resolve("photo.jpg").toString())
				.targetPath(root.resolve("organized").resolve("photo.jpg").toString())
				.status(MovementStatus.MOVED).movedAt(LocalDateTime.now()).build());

		entityManager.clear();

		catalogMutations.forgetLibrary(forgotten.toString());

		entityManager.clear();

		Movement kept = movementRepository.findById(movement.getId()).orElseThrow();

		Assertions.assertThat(kept.getCatalogFile()).isNull();
		Assertions.assertThat(kept.getSourcePath()).endsWith("photo.jpg");

		// And the run that made it is still on the executions screen.
		Assertions.assertThat(executionRepository.findById(execution.getId())).isPresent();
	}

	/**
	 * Forgetting an already forgotten library removes nothing and fails at nothing,
	 * which is what lets a switch interrupted halfway simply run again.
	 */
	@Test
	void forgettingTwiceIsForgettingOnce(@TempDir Path root) throws IOException {
		Path forgotten = Files.createDirectories(root.resolve("A"));

		persist(forgotten.resolve("photo.jpg"));

		entityManager.clear();

		Assertions.assertThat(catalogMutations.forgetLibrary(forgotten.toString())).isEqualTo(1);

		entityManager.clear();

		Assertions.assertThat(catalogMutations.forgetLibrary(forgotten.toString())).isZero();
	}

	/**
	 * A sibling folder whose name merely starts with the library's is a different
	 * library. Matching is by path prefix, and a prefix without the separator would
	 * take {@code C:/media-old} away with {@code C:/media}.
	 */
	@Test
	void aFolderThatMerelyStartsWithTheSameNameIsADifferentLibrary(@TempDir Path root) throws IOException {
		Path forgotten = Files.createDirectories(root.resolve("media"));
		Path lookalike = Files.createDirectories(root.resolve("media-old"));

		persist(forgotten.resolve("photo.jpg"));

		CatalogFile neighbour = persist(lookalike.resolve("photo.jpg"));

		entityManager.clear();

		Assertions.assertThat(catalogMutations.forgetLibrary(forgotten.toString())).isEqualTo(1);

		entityManager.clear();

		Assertions.assertThat(catalogFileRepository.findById(neighbour.getId())).isPresent();
	}

	private CatalogFile persist(Path file) {
		CatalogFile saved = catalogFileRepository.saveAndFlush(CatalogFile.builder().publicId(UUID.randomUUID())
				.fileKey(file.toString()).fileName(file.getFileName().toString()).extension("jpg").sizeBytes(10L)
				.fileType(FileType.PHOTO).lifecycleStatus(LifecycleStatus.ACTIVE).modifiedAt(LocalDateTime.now())
				.importedAt(LocalDateTime.now()).build());

		saved.setLocation(CatalogFileLocation.builder().catalogFile(saved).currentPath(file.toString())
				.currentFolder(file.getParent().toString()).originalPath(file.toString())
				.originalFolder(file.getParent().toString()).inventoryPath(file.toString()).build());

		return catalogFileRepository.saveAndFlush(saved);
	}
}