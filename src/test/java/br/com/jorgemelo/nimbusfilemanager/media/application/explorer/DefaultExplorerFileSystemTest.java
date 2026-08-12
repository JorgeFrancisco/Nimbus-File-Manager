package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationMoveVerifier;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureFileMove;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureLibraryFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWriteOff;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;

/**
 * The disk side of the explorer deletion. What is worth pinning here is the
 * Windows behaviour that broke a real deletion: folders synced from a phone
 * arrive carrying the read-only attribute, and Windows refuses to delete
 * anything that carries it - the WhatsApp media folders are all like that.
 */
class DefaultExplorerFileSystemTest {

	private final DefaultExplorerFileSystem fileSystem = new DefaultExplorerFileSystem(libraryFiles());

	@Test
	void deletesAFileAndReportsIt(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		Assertions.assertThat(fileSystem.deleteRecursively(file, 9L)).isEqualTo(1);
		Assertions.assertThat(file).doesNotExist();
	}

	@Test
	void deletesAWholeTreeCountingOnlyItsFiles(@TempDir Path parent) throws IOException {
		Path album = Files.createDirectory(parent.resolve("album"));
		Path nested = Files.createDirectory(album.resolve("2008"));

		Files.createFile(album.resolve("a.jpg"));
		Files.createFile(nested.resolve("b.jpg"));

		Assertions.assertThat(fileSystem.deleteRecursively(album, 9L)).isEqualTo(2);
		Assertions.assertThat(album).doesNotExist();
	}

	@Test
	void removesTheFolderSkeletonOnceNoFileIsLeft(@TempDir Path parent) throws IOException {
		Path album = Files.createDirectory(parent.resolve("album"));

		Files.createDirectory(album.resolve("Sent"));
		Files.createDirectory(album.resolve("Private"));

		fileSystem.deleteEmptyTree(album, 9L);

		Assertions.assertThat(album).doesNotExist();
	}

	@Test
	void leavesTheFolderAloneWhileAFileRemains(@TempDir Path parent) throws IOException {
		Path album = Files.createDirectory(parent.resolve("album"));

		Files.createFile(album.resolve("a.jpg"));

		fileSystem.deleteEmptyTree(album, 9L);

		Assertions.assertThat(album).exists();
	}

	/**
	 * The case that failed on the real library: a read-only folder tree, which
	 * Windows refuses to delete until the attribute is cleared.
	 */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void clearsTheReadOnlyAttributeWindowsRefusesToDeleteThrough(@TempDir Path parent) throws IOException {
		Path album = Files.createDirectory(parent.resolve("album"));
		Path sent = Files.createDirectory(album.resolve("Sent"));

		readOnly(sent);
		readOnly(album);

		fileSystem.deleteEmptyTree(album, 9L);

		Assertions.assertThat(album).doesNotExist();
	}

	private void readOnly(Path path) throws IOException {
		Files.getFileAttributeView(path, DosFileAttributeView.class).setReadOnly(true);
	}

	/**
	 * The real port over a real registry: what is being tested is a deletion that
	 * actually happens on disk and is actually announced, so a mock here would
	 * assert that a method was called rather than that a file went away.
	 */
	private static SecureLibraryFiles libraryFiles() {
		SelfWrittenPathRegistry registry = new SelfWriteOff();

		return new SecureLibraryFiles(new SecureFileMove(new OrganizationMoveVerifier(new FileHashService()),
				registry), registry);
	}
}