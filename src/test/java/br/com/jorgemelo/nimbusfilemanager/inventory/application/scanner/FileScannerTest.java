package br.com.jorgemelo.nimbusfilemanager.inventory.application.scanner;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.ScanOptions;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.ScannedFile;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;

/**
 * Exercises the shared filtering/walk pipeline through the surviving
 * {@link FileScanner#stream}/{@link FileScanner#count} API (the old
 * callback-driven {@code scan}/{@code scanSorted} entry points were removed).
 * Both entry points run the exact same {@code filteredFiles}/walk pipeline, so
 * these keep covering the physical-file policy (symlink/junction/.lnk
 * exclusion), quarantine skipping, hidden/extension/folder filters and
 * unreadable-directory tolerance.
 */
class FileScannerTest {

	@TempDir
	Path tempDir;

	private final FileScanner scanner = new FileScanner();

	@Test
	void shouldStreamRecursivelyFilteringIncludedAndExcludedExtensions() throws Exception {
		Files.writeString(tempDir.resolve("photo.jpg"), "jpg");
		Files.writeString(tempDir.resolve("skip.tmp"), "tmp");

		Path nested = Files.createDirectory(tempDir.resolve("nested"));

		Files.writeString(nested.resolve("video.mp4"), "mp4");

		List<Path> files = stream(tempDir, new ScanOptions(true, true, List.of(".jpg", "mp4"), List.of("tmp")));

		Assertions.assertThat(files).extracting(path -> path.getFileName().toString())
				.containsExactlyInAnyOrder("photo.jpg", "video.mp4");
	}

	/**
	 * A scan reports a percentage, and a percentage needs a denominator that was
	 * counted by the same rules the walk itself applies - otherwise the bar reaches
	 * a hundred before the work does, or never gets there.
	 */
	@Test
	void countsExactlyTheFilesTheWalkWouldVisit() throws Exception {
		Files.writeString(tempDir.resolve("photo.jpg"), "jpg");
		Files.writeString(tempDir.resolve("skip.tmp"), "tmp");

		Path nested = Files.createDirectory(tempDir.resolve("nested"));

		Files.writeString(nested.resolve("video.mp4"), "mp4");

		ScanOptions options = new ScanOptions(true, true, List.of(".jpg", "mp4"), List.of("tmp"));

		Assertions.assertThat(scanner.count(tempDir, options)).isEqualTo(stream(tempDir, options).size()).isEqualTo(2);

		Assertions.assertThat(scanner.count(tempDir, new ScanOptions(false, true, List.of(), List.of())))
				.as("the shallow walk counts what the shallow walk visits").isEqualTo(2);
	}

	@Test
	void countingSomewhereThatIsNotThereIsRefusedLikeWalkingIt() {
		Path missing = tempDir.resolve("nowhere");

		ScanOptions options = ScanOptions.defaultOptions();

		Assertions.assertThatThrownBy(() -> scanner.count(missing, options))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void shouldReturnOnlyTopLevelWhenRecursiveIsFalse() throws Exception {
		Files.writeString(tempDir.resolve("photo.jpg"), "jpg");

		Path nested = Files.createDirectory(tempDir.resolve("nested"));

		Files.writeString(nested.resolve("video.mp4"), "mp4");

		List<Path> files = stream(tempDir, new ScanOptions(false, true, List.of(), List.of()));

		Assertions.assertThat(files).extracting(path -> path.getFileName().toString())
				.containsExactlyInAnyOrder("photo.jpg");
	}

	@Test
	void shouldExcludeConfiguredExtensions() throws Exception {
		Files.writeString(tempDir.resolve("photo.jpg"), "jpg");
		Files.writeString(tempDir.resolve("skip.tmp"), "tmp");

		List<Path> files = stream(tempDir, new ScanOptions(true, true, List.of(), List.of("tmp")));

		Assertions.assertThat(files).extracting(path -> path.getFileName().toString())
				.containsExactlyInAnyOrder("photo.jpg");
	}

	@Test
	void shouldExcludeConfiguredFoldersByExactNameAndWildcard() throws Exception {
		Files.writeString(tempDir.resolve("photo.jpg"), "jpg");

		Path gitFolder = Files.createDirectory(tempDir.resolve(".git"));
		Path cacheFolder = Files.createDirectory(tempDir.resolve("cache-2026"));

		Files.writeString(gitFolder.resolve("ignored.jpg"), "git");
		Files.writeString(cacheFolder.resolve("ignored-too.jpg"), "cache");

		List<Path> files = stream(tempDir,
				new ScanOptions(true, true, List.of(), List.of(), List.of(".git", "cache-*")));

		Assertions.assertThat(files).extracting(path -> path.getFileName().toString()).containsExactly("photo.jpg");
	}

	@Test
	void shouldSkipConfiguredQuarantineSubtreeWhenRecursive() throws Exception {
		Files.writeString(tempDir.resolve("keep.jpg"), "jpg");

		Path quarantine = Files.createDirectory(tempDir.resolve("QUARENTENA"));
		Path exec = Files.createDirectory(quarantine.resolve("exec-13"));

		Files.writeString(exec.resolve("trashed.jpg"), "jpg");

		List<Path> files = stream(quarantineAwareScanner(quarantine), tempDir,
				new ScanOptions(true, true, List.of(), List.of()));

		Assertions.assertThat(files).extracting(path -> path.getFileName().toString()).containsExactly("keep.jpg");
	}

	@Test
	void shouldSkipQuarantineFilesEvenWhenNotRecursive() throws Exception {
		Path quarantine = Files.createDirectory(tempDir.resolve("QUARENTENA"));

		Files.writeString(quarantine.resolve("trashed.jpg"), "jpg");

		// Listing the quarantine folder itself, non-recursively: the per-file filter
		// (not the walk's SKIP_SUBTREE) is what drops the files here.
		List<Path> files = stream(quarantineAwareScanner(quarantine), quarantine,
				new ScanOptions(false, true, List.of(), List.of()));

		Assertions.assertThat(files).isEmpty();
	}

	@Test
	void shouldUseDefaultOptionsAndIgnoreBlankExtensionFilters() throws Exception {
		Files.writeString(tempDir.resolve("photo.JPG"), "jpg");
		Files.writeString(tempDir.resolve("video.mp4"), "mp4");

		List<Path> defaultFiles = stream(tempDir, null);
		List<Path> filteredFiles = stream(tempDir,
				new ScanOptions(true, true, Arrays.asList(" ", null, "jpg"), Arrays.asList(" ", null)));

		Assertions.assertThat(defaultFiles).hasSize(2);
		Assertions.assertThat(filteredFiles).extracting(path -> path.getFileName().toString())
				.containsExactlyInAnyOrder("photo.JPG");
	}

	/**
	 * Null filter lists mean "no filter", not "match nothing" - the request DTO
	 * lets them through, so the scanner has to normalize them itself.
	 */
	@Test
	void shouldTreatNullExtensionAndFolderFiltersAsNoFilter() throws Exception {
		Files.writeString(tempDir.resolve("photo.jpg"), "jpg");
		Files.writeString(tempDir.resolve("notes.txt"), "txt");

		List<Path> files = stream(tempDir, new ScanOptions(true, true, null, null, null));

		Assertions.assertThat(files).extracting(path -> path.getFileName().toString())
				.containsExactlyInAnyOrder("photo.jpg", "notes.txt");
	}

	/**
	 * A source folder that does not exist is a caller error surfaced up front by
	 * both entry points, not an empty scan that silently reports zero files.
	 */
	@Test
	void shouldRejectASourcePathThatDoesNotExist() {
		Path missing = tempDir.resolve("does-not-exist");

		ScanOptions options = new ScanOptions(true, true, List.of(), List.of());

		Assertions.assertThatThrownBy(() -> scanner.count(missing, options))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Path does not exist");
		Assertions.assertThatThrownBy(() -> scanner.stream(missing, options))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Path does not exist");
	}

	@Test
	void shouldExcludeHiddenFilesWhenIncludeHiddenIsFalse() throws Exception {
		Files.writeString(tempDir.resolve("photo.jpg"), "jpg");

		createHiddenFile(tempDir, "hidden.jpg");

		List<Path> files = stream(tempDir, new ScanOptions(true, false, List.of(), List.of()));

		Assertions.assertThat(files).extracting(path -> path.getFileName().toString()).containsExactly("photo.jpg");
	}

	@Test
	void shouldSkipHiddenDirectorySubtreeEvenWhenFilesInsideAreNotHidden() throws Exception {
		Files.writeString(tempDir.resolve("photo.jpg"), "jpg");

		Path visibleDirectory = Files.createDirectory(tempDir.resolve("albums"));
		Files.writeString(visibleDirectory.resolve("kept.jpg"), "jpg");

		Path hiddenDirectory = createHiddenDirectory(tempDir, "recycle");
		Files.writeString(hiddenDirectory.resolve("trashed.jpg"), "jpg");

		List<Path> files = stream(tempDir, new ScanOptions(true, false, List.of(), List.of()));

		Assertions.assertThat(files).extracting(path -> path.getFileName().toString())
				.containsExactlyInAnyOrder("photo.jpg", "kept.jpg");
	}

	@Test
	void shouldIncludeHiddenDirectorySubtreeWhenIncludeHiddenIsTrue() throws Exception {
		Files.writeString(tempDir.resolve("photo.jpg"), "jpg");

		Path hiddenDirectory = createHiddenDirectory(tempDir, "recycle");
		Files.writeString(hiddenDirectory.resolve("trashed.jpg"), "jpg");

		List<Path> files = stream(tempDir, new ScanOptions(true, true, List.of(), List.of()));

		Assertions.assertThat(files).extracting(path -> path.getFileName().toString())
				.containsExactlyInAnyOrder("photo.jpg", "trashed.jpg");
	}

	@Test
	void shouldMatchExcludedFolderUsingSingleCharacterWildcard() throws Exception {
		Files.writeString(tempDir.resolve("photo.jpg"), "jpg");

		Path cacheFolder = Files.createDirectory(tempDir.resolve("cache1"));

		Files.writeString(cacheFolder.resolve("ignored.jpg"), "cache");

		List<Path> files = stream(tempDir, new ScanOptions(true, true, List.of(), List.of(), List.of("cache?")));

		Assertions.assertThat(files).extracting(path -> path.getFileName().toString()).containsExactly("photo.jpg");
	}

	@Test
	void shouldIgnoreSymbolicLinkFiles() throws Exception {
		Files.writeString(tempDir.resolve("photo.jpg"), "jpg");

		assumeSymlink(tempDir.resolve("shortcut.jpg"), tempDir.resolve("photo.jpg"));

		List<Path> files = stream(tempDir, new ScanOptions(true, true, List.of(), List.of()));

		Assertions.assertThat(files).extracting(path -> path.getFileName().toString()).containsExactly("photo.jpg");
	}

	@Test
	void shouldNotDescendIntoSymbolicLinkDirectories() throws Exception {
		Path real = Files.createDirectory(tempDir.resolve("real"));

		Files.writeString(real.resolve("inside.jpg"), "jpg");

		assumeSymlink(tempDir.resolve("linkdir"), real);

		List<Path> files = stream(tempDir, new ScanOptions(true, true, List.of(), List.of()));

		// "inside.jpg" is reached once through the real directory, never a second
		// time through the symlinked one.
		Assertions.assertThat(files).extracting(path -> path.getFileName().toString()).containsExactly("inside.jpg");
	}

	@Test
	void shouldIgnoreLnkShortcutsCaseInsensitively() throws Exception {
		Files.writeString(tempDir.resolve("photo.jpg"), "jpg");
		Files.writeString(tempDir.resolve("shortcut.lnk"), "lnk");
		Files.writeString(tempDir.resolve("OTHER.LNK"), "lnk");

		// No extension exclusion here: this proves the physical-file policy (not the
		// extension filter) is what drops .lnk / .LNK.
		List<Path> files = stream(tempDir, new ScanOptions(true, true, List.of(), List.of()));

		Assertions.assertThat(files).extracting(path -> path.getFileName().toString()).containsExactly("photo.jpg");
	}

	@Test
	void shouldNotLoopOnCircularSymbolicLink() throws Exception {
		Path child = Files.createDirectory(tempDir.resolve("child"));

		Files.writeString(child.resolve("photo.jpg"), "jpg");

		assumeSymlink(child.resolve("loop"), tempDir);

		List<Path> files = stream(tempDir, new ScanOptions(true, true, List.of(), List.of()));

		Assertions.assertThat(files).extracting(path -> path.getFileName().toString()).containsExactly("photo.jpg");
	}

	@Test
	void shouldSkipUnreadableDirectoriesInsteadOfFailingTheWholeScan() throws Exception {
		Files.writeString(tempDir.resolve("photo.jpg"), "jpg");

		Path protectedDir = Files.createDirectory(tempDir.resolve("protected"));

		Files.writeString(protectedDir.resolve("inside.jpg"), "jpg");

		try {
			Files.setPosixFilePermissions(protectedDir, PosixFilePermissions.fromString("---------"));
		} catch (UnsupportedOperationException | IOException exception) {
			Assumptions.abort("POSIX permissions not supported (e.g. Windows): " + exception.getMessage());
		}

		List<Path> files;

		try {
			// A protected/unreadable directory (like Windows "System Volume
			// Information" at a drive root) must not abort the whole scan.
			files = stream(tempDir, new ScanOptions(true, true, List.of(), List.of()));
		} finally {
			Files.setPosixFilePermissions(protectedDir, PosixFilePermissions.fromString("rwx------"));
		}

		Assertions.assertThat(files).extracting(path -> path.getFileName().toString()).contains("photo.jpg");
	}

	@Test
	void shouldCountFilesMatchingTheConfiguredFilters() throws Exception {
		Files.writeString(tempDir.resolve("photo.jpg"), "jpg");
		Files.writeString(tempDir.resolve("skip.tmp"), "tmp");

		Path nested = Files.createDirectory(tempDir.resolve("nested"));

		Files.writeString(nested.resolve("video.mp4"), "mp4");

		long total = scanner.count(tempDir, new ScanOptions(true, true, List.of(".jpg", "mp4"), List.of("tmp")));

		Assertions.assertThat(total).isEqualTo(2);
	}

	@Test
	void shouldCountUsingDefaultOptionsWhenNoneProvided() throws Exception {
		Files.writeString(tempDir.resolve("photo.JPG"), "jpg");
		Files.writeString(tempDir.resolve("video.mp4"), "mp4");

		Assertions.assertThat(scanner.count(tempDir, null)).isEqualTo(2);
	}

	@Test
	void shouldStreamUsingDefaultOptionsWhenNoneProvided() throws Exception {
		Files.writeString(tempDir.resolve("photo.JPG"), "jpg");
		Files.writeString(tempDir.resolve("video.mp4"), "mp4");

		try (var stream = scanner.stream(tempDir, null)) {
			Assertions.assertThat(stream.count()).isEqualTo(2);
		}
	}

	@Test
	void streamShouldRejectInvalidInput() throws Exception {
		Path file = Files.writeString(tempDir.resolve("photo.jpg"), "jpg");

		assertThatIllegalArgumentException().isThrownBy(() -> scanner.stream(null, null))
				.withMessage("Source path must not be null.");

		assertThatIllegalArgumentException().isThrownBy(() -> scanner.stream(tempDir.resolve("missing"), null))
				.withMessageContaining("Path does not exist");

		assertThatIllegalArgumentException().isThrownBy(() -> scanner.stream(file, null))
				.withMessageContaining("Path is not a directory");
	}

	@Test
	void countShouldRejectInvalidInput() {
		assertThatIllegalArgumentException().isThrownBy(() -> scanner.count(null, null))
				.withMessage("Source path must not be null.");

		assertThatIllegalArgumentException().isThrownBy(() -> scanner.count(tempDir.resolve("missing"), null))
				.withMessageContaining("Path does not exist");
	}

	private List<Path> stream(Path sourcePath, ScanOptions options) {
		return stream(scanner, sourcePath, options);
	}

	/**
	 * What the walk found, as paths. The two facts it also carries - the size and
	 * the timestamp the operating system handed it - are the subject of their own
	 * checks; these are about which files are visited at all.
	 */
	private List<Path> stream(FileScanner fileScanner, Path sourcePath, ScanOptions options) {
		try (var stream = fileScanner.stream(sourcePath, options)) {
			return stream.map(ScannedFile::path).toList();
		}
	}

	private FileScanner quarantineAwareScanner(Path quarantineFolder) {
		AppSettingService settings = mock(AppSettingService.class);

		when(settings.stringValue(eq(SettingsConstants.TRASH_FOLDER), anyString()))
				.thenReturn(quarantineFolder.toString());

		return new FileScanner(new ScanExclusionService(settings));
	}

	/**
	 * {@code Files.isHidden}, which {@link FileScanner} relies on, is OS-dependent:
	 * Windows checks the DOS "hidden" file attribute, while POSIX (Linux/macOS -
	 * e.g. CI) only ever looks at whether the file name starts with a dot, and
	 * doesn't support the "dos" attribute view at all. Creating the file the way
	 * that's actually hidden on the current OS keeps this test meaningful
	 * cross-platform instead of only passing on Windows.
	 */
	private Path createHiddenFile(Path dir, String fileName) throws IOException {
		if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
			Path file = Files.writeString(dir.resolve(fileName), "hidden");

			Files.setAttribute(file, "dos:hidden", true);

			return file;
		}

		return Files.writeString(dir.resolve("." + fileName), "hidden");
	}

	/**
	 * Directory counterpart of {@link #createHiddenFile}: hidden via the DOS
	 * attribute on Windows and via a dot-prefixed name on POSIX, so the subtree is
	 * actually hidden on the current OS. Mirrors how {@code $RECYCLE.BIN} is hidden
	 * at the folder level while the files inside it are not individually flagged.
	 */
	private Path createHiddenDirectory(Path parent, String name) throws IOException {
		if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
			Path directory = Files.createDirectory(parent.resolve(name));

			Files.setAttribute(directory, "dos:hidden", true);

			return directory;
		}

		return Files.createDirectory(parent.resolve("." + name));
	}

	private void assumeSymlink(Path link, Path target) {
		try {
			Files.createSymbolicLink(link, target);
		} catch (IOException | UnsupportedOperationException | SecurityException exception) {
			Assumptions.abort("Symbolic links are not supported in this environment: " + exception.getMessage());
		}
	}
}