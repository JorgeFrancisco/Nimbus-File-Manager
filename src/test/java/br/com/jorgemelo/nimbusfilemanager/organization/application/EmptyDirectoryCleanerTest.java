package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmptyDirectoryCleanerTest {

	private final EmptyDirectoryCleaner cleaner = new EmptyDirectoryCleaner();

	@Test
	void removesEmptyDirectoryAndWalksUpUntilANonEmptyParent(@TempDir Path root) throws Exception {
		Path a = Files.createDirectories(root.resolve("a"));
		Path b = Files.createDirectories(a.resolve("b"));
		Path c = Files.createDirectories(b.resolve("c"));

		List<Path> removed = cleaner.removeEmptyAncestors(c, root);

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

		List<Path> removed = cleaner.removeEmptyAncestors(b, root);

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

		List<Path> removed = cleaner.removeEmptyAncestors(a, root);

		Assertions.assertThat(removed).isEmpty();
		Assertions.assertThat(Files.exists(a)).isTrue();
	}

	@Test
	void neverRemovesTheBoundaryItself(@TempDir Path root) {
		List<Path> removed = cleaner.removeEmptyAncestors(root, root);

		Assertions.assertThat(removed).isEmpty();
		Assertions.assertThat(Files.exists(root)).isTrue();
	}

	@Test
	void ignoresDirectoriesOutsideTheBoundary(@TempDir Path root) throws Exception {
		Path outside = Files.createDirectories(root.resolve("outside"));
		Path boundary = Files.createDirectories(root.resolve("boundary"));

		List<Path> removed = cleaner.removeEmptyAncestors(outside, boundary);

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

		cleaner.removeEmptyAncestors(child, root);

		Assertions.assertThat(Files.exists(child)).isTrue();
	}

	/** A regular file is never mistaken for an empty folder. */
	@Test
	void neverRemovesARegularFile(@TempDir Path root) throws Exception {
		Path file = Files.writeString(root.resolve("a.txt"), "x");

		cleaner.removeEmptyAncestors(file, root);

		Assertions.assertThat(Files.exists(file)).isTrue();
	}

	@Test
	void isNullSafe() {
		Assertions.assertThat(cleaner.removeEmptyAncestors(null, Path.of("x"))).isEmpty();
		Assertions.assertThat(cleaner.removeEmptyAncestors(Path.of("x"), null)).isEmpty();
	}
}