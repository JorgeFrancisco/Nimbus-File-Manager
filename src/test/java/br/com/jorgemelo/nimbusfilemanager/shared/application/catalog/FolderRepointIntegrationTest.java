package br.com.jorgemelo.nimbusfilemanager.shared.application.catalog;

import java.nio.file.Path;
import java.time.LocalDateTime;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Renaming a folder moves everything under it in one operating-system call, and
 * this is the statement that has to keep up with it.
 *
 * <p>
 * Against a real Postgres because that is where it runs: the prefix is matched
 * by a fixed-length head rather than by {@code LIKE}, precisely so a path full
 * of separators - the escape character, on Windows - and file names full of
 * {@code _} and {@code %} - the wildcards - cannot change what it matches. A
 * test over mocks would prove none of that.
 *
 * <p>
 * The folders are real and absolute, under a temporary root, and the reason is
 * that the repoint is not the pure string rewrite this class used to claim it
 * was: it normalizes what it is given and takes the separator from the running
 * file system. Windows-shaped literals therefore described nothing on Linux - a
 * single relative segment, prefixed with the working directory - and the
 * statement matched no row at all. Nothing is written to disk even so; the root
 * is borrowed only for its shape.
 *
 * <p>
 * What the names carry is the whole point and survives the change: a {@code %}
 * and an {@code _} that must not act as wildcards, and a sibling folder whose
 * name merely begins the same.
 */
class FolderRepointIntegrationTest extends SharedPostgresIntegrationTest {

	@TempDir
	Path library;

	@Autowired
	private CatalogMutations catalogMutations;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	private String oldFolder;
	private String newFolder;
	private String siblingFolder;

	@BeforeEach
	void nameTheFolders() {
		oldFolder = at("fotos", "album_2008");
		newFolder = at("fotos", "viagem 100%");
		siblingFolder = at("fotos", "album_2009");
	}

	@Test
	void movesEveryCataloguedFileUnderTheFolderAndLeavesTheNeighboursAlone() {
		Long direct = catalogued(under(oldFolder, "a_1.jpg"), oldFolder);
		Long deep = catalogued(under(oldFolder, "2008", "praia", "b%2.jpg"), under(oldFolder, "2008", "praia"));
		Long sibling = catalogued(under(siblingFolder, "c.jpg"), siblingFolder);

		int repointed = catalogMutations.repointFolder(oldFolder, newFolder, LocalDateTime.now());

		Assertions.assertThat(repointed).isEqualTo(2);
		Assertions.assertThat(keyOf(direct)).isEqualTo(under(newFolder, "a_1.jpg"));
		Assertions.assertThat(keyOf(deep)).isEqualTo(under(newFolder, "2008", "praia", "b%2.jpg"));
		Assertions.assertThat(keyOf(sibling)).as("a folder whose name merely starts the same is not under it")
				.isEqualTo(under(siblingFolder, "c.jpg"));
	}

	/**
	 * The placement moves with the entry, and the folder column with it. Files
	 * sitting directly in the renamed folder have it stored without a trailing
	 * separator, so the prefix that matches their path does not match their folder
	 * - which is the case that has to be handled rather than assumed away.
	 */
	@Test
	void movesThePlacementAndTheFolderItRecords() {
		Long direct = catalogued(under(oldFolder, "a.jpg"), oldFolder);
		Long deep = catalogued(under(oldFolder, "2008", "b.jpg"), under(oldFolder, "2008"));

		catalogMutations.repointFolder(oldFolder, newFolder, LocalDateTime.now());

		Assertions.assertThat(currentPathOf(direct)).isEqualTo(under(newFolder, "a.jpg"));
		Assertions.assertThat(currentFolderOf(direct)).isEqualTo(newFolder);
		Assertions.assertThat(currentPathOf(deep)).isEqualTo(under(newFolder, "2008", "b.jpg"));
		Assertions.assertThat(currentFolderOf(deep)).isEqualTo(under(newFolder, "2008"));
	}

	/**
	 * Where the file was first found is history, and a rename does not rewrite
	 * history.
	 */
	@Test
	void leavesTheOriginalPlacementUntouched() {
		Long direct = catalogued(under(oldFolder, "a.jpg"), oldFolder);

		catalogMutations.repointFolder(oldFolder, newFolder, LocalDateTime.now());

		Assertions.assertThat(catalogFileLocationRepository.findById(direct).orElseThrow().getOriginalPath())
				.isEqualTo(under(oldFolder, "a.jpg"));
	}

	/** A folder under the temporary root, named the way this file system names. */
	private String at(String... names) {
		return PathUtils.normalize(Path.of(library.toString(), names));
	}

	private String under(String folder, String... names) {
		return PathUtils.normalize(Path.of(folder, names));
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
		CatalogFile file = CatalogFile.builder().fileKey(path)
				.fileName(Path.of(path).getFileName().toString()).extension("jpg").sizeBytes(4L)
				.modifiedAt(LocalDateTime.now()).fileType(FileType.PHOTO).lifecycleStatus(LifecycleStatus.ACTIVE)
				.build();
		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(path).currentFolder(folder)
				.originalPath(path).originalFolder(folder).build());

		return catalogFileRepository.saveAndFlush(file).getId();
	}
}
