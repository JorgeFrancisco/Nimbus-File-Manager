package br.com.jorgemelo.nimbusfilemanager.settings.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.FolderBrowserEntry;

class FolderBrowserServiceTest {

	@TempDir
	Path tempDir;

	private final FolderBrowserService service = new FolderBrowserService();

	@Test
	void shouldListOnlyDirectSubdirectories() throws Exception {
		Path album = Files.createDirectory(tempDir.resolve("Álbum"));

		Files.writeString(tempDir.resolve("foto.jpg"), "test");

		var result = service.browse(tempDir.toString());

		assertThat(result.currentPath()).isEqualTo(tempDir.toAbsolutePath().normalize().toString());
		assertThat(result.directories()).extracting(FolderBrowserEntry::path)
				.containsExactly(album.toAbsolutePath().normalize().toString());
	}

	@Test
	void shouldExposeFileSystemRootsAndRejectFiles() throws Exception {
		assertThat(service.browse(null).directories()).isNotEmpty();

		Path file = Files.writeString(tempDir.resolve("not-a-folder.txt"), "test");

		assertThatIllegalArgumentException().isThrownBy(() -> service.browse(file.toString()));
	}

	@Test
	void shouldHideHiddenAndSystemFolders() throws Exception {
		Files.createDirectory(tempDir.resolve("Fotos"));
		Files.createDirectory(tempDir.resolve(".config"));
		Files.createDirectory(tempDir.resolve("$RECYCLE.BIN"));

		var result = service.browse(tempDir.toString());

		assertThat(result.directories()).extracting(FolderBrowserEntry::name).containsExactly("Fotos");
	}

	@Test
	void formatRootNameAppendsVolumeLabelOnlyWhenHelpful() {
		assertThat(FolderBrowserService.formatRootName("G:\\", "Google Drive")).isEqualTo("G:\\ (Google Drive)");
		assertThat(FolderBrowserService.formatRootName("C:\\", "")).isEqualTo("C:\\");
		assertThat(FolderBrowserService.formatRootName("C:\\", null)).isEqualTo("C:\\");
		assertThat(FolderBrowserService.formatRootName("C:\\", "C:\\")).isEqualTo("C:\\");
		assertThat(FolderBrowserService.formatRootName("D:\\", "\\\\?\\Volume{abc}")).isEqualTo("D:\\");
	}

	/**
	 * A label that only repeats the drive letter, or one long enough to be a
	 * description rather than a name, adds nothing to the root.
	 */
	@Test
	void formatRootNameSkipsLabelsThatRepeatTheRootOrAreTooLong() {
		assertThat(FolderBrowserService.formatRootName("C:\\", " c: ")).isEqualTo("C:\\");
		assertThat(FolderBrowserService.formatRootName("E:\\", "x".repeat(41))).isEqualTo("E:\\");
		assertThat(FolderBrowserService.formatRootName("E:\\", "x".repeat(40)))
				.isEqualTo("E:\\ (" + "x".repeat(40) + ")");
	}

	@Test
	void shouldTreatABlankPathAsTheRootListing() {
		assertThat(service.browse("   ").currentPath()).isNull();
		assertThat(service.browse("   ").directories()).isNotEmpty();
	}

	/**
	 * The listing is capped so a folder with tens of thousands of subfolders never
	 * builds an unbounded response; the flag tells the screen it was cut.
	 */
	@Test
	void shouldCapTheListingAndFlagItAsTruncated() throws Exception {
		for (int i = 0; i <= 1000; i++) {
			Files.createDirectory(tempDir.resolve("folder-" + i));
		}

		var result = service.browse(tempDir.toString());

		assertThat(result.directories()).hasSize(1000);
		assertThat(result.truncated()).isTrue();
	}

	@Test
	void shouldReportNoParentWhenBrowsingAFileSystemRoot() {
		Path root = tempDir.getRoot();

		var result = service.browse(root.toString());

		assertThat(result.currentPath()).isEqualTo(root.toString());
		assertThat(result.parentPath()).isNull();
	}
}