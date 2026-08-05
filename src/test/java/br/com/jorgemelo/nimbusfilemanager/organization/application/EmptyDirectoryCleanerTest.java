package br.com.jorgemelo.nimbusfilemanager.organization.application;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.InMemorySelfWrittenPaths;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;
import br.com.jorgemelo.nimbusfilemanager.shared.application.library.LibraryFileMutations;

class EmptyDirectoryCleanerTest {

	private final EmptyDirectoryCleaner cleaner = new EmptyDirectoryCleaner(libraryFiles());

	@Test
	void removesEmptyDirectoryAndWalksUpUntilANonEmptyParent(@TempDir Path root) throws Exception {
		Path a = Files.createDirectories(root.resolve("a"));
		Path b = Files.createDirectories(a.resolve("b"));
		Path c = Files.createDirectories(b.resolve("c"));

		List<Path> removed = cleaner.removeEmptyAncestors(c, root, 42L);

		Assertions.assertThat(removed).containsExactly(c, b, a);
		Assertions.assertThat(Files.exists(c)).isFalse();
		Assertions.assertThat(Files.exists(b)).isFalse();
		Assertions.assertThat(Files.exists(a)).isFalse();
		Assertions.assertThat(Files.exists(root)).isTrue();
	}

	@Test
	void stopsAtTheFirstParentThatStillHoldsContent(@TempDir Path root) throws Exception {
		Path a = Files.createDirectories(root.resolve("a"));
		Path b = Files.createDirectories(a.resolve("b"));

		Files.writeString(a.resolve("keep.txt"), "still here");

		List<Path> removed = cleaner.removeEmptyAncestors(b, root, 42L);

		Assertions.assertThat(removed).containsExactly(b);
		Assertions.assertThat(Files.exists(b)).isFalse();
		Assertions.assertThat(Files.exists(a)).isTrue();
	}

	@Test
	void neverRemovesADirectoryThatStillHasAHiddenOrSystemFile(@TempDir Path root) throws Exception {
		Path a = Files.createDirectories(root.resolve("a"));

		// A dot-file is hidden on POSIX; regardless of platform a listed entry means
		// "not empty".
		Files.writeString(a.resolve(".thumbs"), "x");

		List<Path> removed = cleaner.removeEmptyAncestors(a, root, 42L);

		Assertions.assertThat(removed).isEmpty();
		Assertions.assertThat(Files.exists(a)).isTrue();
	}

	@Test
	void neverRemovesTheBoundaryItself(@TempDir Path root) {
		List<Path> removed = cleaner.removeEmptyAncestors(root, root, 42L);

		Assertions.assertThat(removed).isEmpty();
		Assertions.assertThat(Files.exists(root)).isTrue();
	}

	@Test
	void ignoresDirectoriesOutsideTheBoundary(@TempDir Path root) throws Exception {
		Path outside = Files.createDirectories(root.resolve("outside"));
		Path boundary = Files.createDirectories(root.resolve("boundary"));

		List<Path> removed = cleaner.removeEmptyAncestors(outside, boundary, 42L);

		Assertions.assertThat(removed).isEmpty();
		Assertions.assertThat(Files.exists(outside)).isTrue();
	}

	/**
	 * A folder that filled up between the check and the delete is left alone, and
	 * the failure is not allowed to break the walk upwards.
	 */
	@Test
	void keepsWalkingWhenADirectoryCannotBeRemoved(@TempDir Path root) throws Exception {
		Path child = Files.createDirectories(root.resolve("parent").resolve("child"));

		Files.writeString(child.resolve("keeps-it-alive.txt"), "x");

		cleaner.removeEmptyAncestors(child, root, 42L);

		Assertions.assertThat(Files.exists(child)).isTrue();
	}

	/**
	 * A folder the port refuses to remove stops the walk where it is. The refusal
	 * is what a real filesystem does when something re-creates a file in the
	 * instant between finding the folder empty and deleting it - and the answer
	 * has to be to leave the rest of the tree alone rather than to keep climbing
	 * past a folder that is still there.
	 */
	@Test
	void stopsClimbingWhenThePortRefusesToRemoveAFolder(@TempDir Path root) throws Exception {
		Path child = Files.createDirectories(root.resolve("parent").resolve("child"));

		LibraryFileMutations refusing = mock(LibraryFileMutations.class);

		doThrow(new DirectoryNotEmptyException(child.toString())).when(refusing)
				.deleteEmptyDirectory(any(), any());

		Assertions.assertThat(new EmptyDirectoryCleaner(refusing).removeEmptyAncestors(child, root, 42L)).isEmpty();
		Assertions.assertThat(Files.exists(child)).isTrue();
	}

	/** A regular file is never mistaken for an empty folder. */
	@Test
	void neverRemovesARegularFile(@TempDir Path root) throws Exception {
		Path file = Files.writeString(root.resolve("a.txt"), "x");

		cleaner.removeEmptyAncestors(file, root, 42L);

		Assertions.assertThat(Files.exists(file)).isTrue();
	}

	@Test
	void isNullSafe() {
		Assertions.assertThat(cleaner.removeEmptyAncestors(null, Path.of("x"), 42L)).isEmpty();
		Assertions.assertThat(cleaner.removeEmptyAncestors(Path.of("x"), null, 42L)).isEmpty();
	}

	/**
	 * The real port over a real registry: what is being tested is a deletion that
	 * actually happens on disk and is actually announced, so a mock here would
	 * assert that a method was called rather than that a file went away.
	 */
	private static SecureLibraryFiles libraryFiles() {
		SelfWrittenPathRegistry registry = new SelfWrittenPathRegistry(new InMemorySelfWrittenPaths(),
				Clock.systemUTC());

		return new SecureLibraryFiles(new SecureFileMove(new OrganizationMoveVerifier(new FileHashService()),
				registry), registry);
	}
}