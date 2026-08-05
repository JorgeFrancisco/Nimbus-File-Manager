package br.com.jorgemelo.nimbusfilemanager.shared.application.catalog;

import java.time.LocalDateTime;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

/**
 * Renaming a folder moves everything under it in one operating-system call, and
 * this is the statement that has to keep up with it.
 *
 * <p>
 * Against a real Postgres because that is where it runs: the prefix is matched
 * by a fixed-length head rather than by {@code LIKE}, precisely so a Windows
 * path full of backslashes - the escape character - and file names full of
 * {@code _} and {@code %} - the wildcards - cannot change what it matches. A
 * test over mocks would prove none of that.
 *
 * <p>
 * The paths here are Windows-shaped strings on purpose and never touch a disk:
 * what is under test is a string rewrite in the database, so it runs the same on
 * a Linux runner.
 */
@SpringBootTest
@Transactional
@Testcontainers
class FolderRepointIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	private static final String OLD_FOLDER = "D:\\fotos\\album_2008";
	private static final String NEW_FOLDER = "D:\\fotos\\viagem 100%";

	@Autowired
	private CatalogMutations catalogMutations;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Test
	void movesEveryCataloguedFileUnderTheFolderAndLeavesTheNeighboursAlone() {
		Long direct = catalogued(OLD_FOLDER + "\\a_1.jpg", OLD_FOLDER);
		Long deep = catalogued(OLD_FOLDER + "\\2008\\praia\\b%2.jpg", OLD_FOLDER + "\\2008\\praia");
		Long sibling = catalogued("D:\\fotos\\album_2009\\c.jpg", "D:\\fotos\\album_2009");

		int repointed = catalogMutations.repointFolder(OLD_FOLDER, NEW_FOLDER, LocalDateTime.now());

		Assertions.assertThat(repointed).isEqualTo(2);
		Assertions.assertThat(keyOf(direct)).isEqualTo(NEW_FOLDER + "\\a_1.jpg");
		Assertions.assertThat(keyOf(deep)).isEqualTo(NEW_FOLDER + "\\2008\\praia\\b%2.jpg");
		Assertions.assertThat(keyOf(sibling)).as("a folder whose name merely starts the same is not under it")
				.isEqualTo("D:\\fotos\\album_2009\\c.jpg");
	}

	/**
	 * The placement moves with the entry, and the folder column with it. Files
	 * sitting directly in the renamed folder have it stored without a trailing
	 * separator, so the prefix that matches their path does not match their folder
	 * - which is the case that has to be handled rather than assumed away.
	 */
	@Test
	void movesThePlacementAndTheFolderItRecords() {
		Long direct = catalogued(OLD_FOLDER + "\\a.jpg", OLD_FOLDER);
		Long deep = catalogued(OLD_FOLDER + "\\2008\\b.jpg", OLD_FOLDER + "\\2008");

		catalogMutations.repointFolder(OLD_FOLDER, NEW_FOLDER, LocalDateTime.now());

		Assertions.assertThat(currentPathOf(direct)).isEqualTo(NEW_FOLDER + "\\a.jpg");
		Assertions.assertThat(currentFolderOf(direct)).isEqualTo(NEW_FOLDER);
		Assertions.assertThat(currentPathOf(deep)).isEqualTo(NEW_FOLDER + "\\2008\\b.jpg");
		Assertions.assertThat(currentFolderOf(deep)).isEqualTo(NEW_FOLDER + "\\2008");
	}

	/**
	 * Where the file was first found is history, and a rename does not rewrite
	 * history.
	 */
	@Test
	void leavesTheOriginalPlacementUntouched() {
		Long direct = catalogued(OLD_FOLDER + "\\a.jpg", OLD_FOLDER);

		catalogMutations.repointFolder(OLD_FOLDER, NEW_FOLDER, LocalDateTime.now());

		Assertions.assertThat(catalogFileLocationRepository.findById(direct).orElseThrow().getOriginalPath())
				.isEqualTo(OLD_FOLDER + "\\a.jpg");
	}

	private String keyOf(Long catalogFileId) {
		return catalogFileRepository.findById(catalogFileId).orElseThrow().getFileKey();
	}

	private String currentPathOf(Long catalogFileId) {
		return catalogFileLocationRepository.findById(catalogFileId).orElseThrow().getCurrentPath();
	}

	private String currentFolderOf(Long catalogFileId) {
		return catalogFileLocationRepository.findById(catalogFileId).orElseThrow().getCurrentFolder();
	}

	private Long catalogued(String path, String folder) {
		CatalogFile file = CatalogFile.builder().fileKey(path).fileName(path.substring(path.lastIndexOf('\\') + 1))
				.extension("jpg").sizeBytes(4L).modifiedAt(LocalDateTime.now()).fileType(FileType.PHOTO)
				.lifecycleStatus(LifecycleStatus.ACTIVE).build();
		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(path).currentFolder(folder)
				.originalPath(path).originalFolder(folder).build());

		return catalogFileRepository.saveAndFlush(file).getId();
	}
}