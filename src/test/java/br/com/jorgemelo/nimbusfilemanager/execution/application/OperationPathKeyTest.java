package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The spellings of one path must all reach one number.
 *
 * <p>
 * This is where the exclusion is won or lost. A collision would merely make two
 * unrelated operations queue behind each other; one path reaching two keys
 * would let two operations mutate the same files while each believes it holds
 * the tree - silently, with no error anywhere. So the assertions here are
 * mostly the same one, repeated for every way a path can be written
 * differently: separator, casing, {@code ..}, trailing separator, and a
 * relative path against the same target.
 */
class OperationPathKeyTest {

	@Test
	void treatsForwardAndBackSlashesAsTheSamePath(@TempDir Path folder) {
		Path target = folder.resolve("fotos").resolve("2008");

		assertThat(keyOf(target)).isEqualTo(keyOf(Path.of(target.toString().replace('\\', '/'))));
	}

	@Test
	void ignoresCasingBecauseWindowsDoes(@TempDir Path folder) {
		Path target = folder.resolve("Fotos");

		assertThat(keyOf(target)).isEqualTo(keyOf(Path.of(target.toString().toUpperCase(Locale.ROOT))));
	}

	@Test
	void resolvesDotDotBeforeHashing(@TempDir Path folder) {
		Path direct = folder.resolve("fotos");
		Path roundabout = folder.resolve("videos").resolve("..").resolve("fotos");

		assertThat(keyOf(direct)).isEqualTo(keyOf(roundabout));
	}

	@Test
	void ignoresATrailingSeparator(@TempDir Path folder) {
		Path target = folder.resolve("fotos");

		assertThat(OperationPathKey.canonical(target)).isEqualTo(OperationPathKey.canonical(Path.of(target + "\\")));
	}

	@Test
	void keepsTheVolumeRootAsItsOwnKey(@TempDir Path folder) {
		assertThat(keyOf(folder.getRoot())).isNotEqualTo(keyOf(folder));
	}

	@Test
	void resolvesAPathThroughItsRealNameOnDisk(@TempDir Path folder) throws Exception {
		Path real = Files.createDirectory(folder.resolve("Biblioteca"));

		assertThat(keyOf(real)).isEqualTo(keyOf(folder.resolve("biblioteca")));
	}

	@Test
	void survivesAPathThatDoesNotExistYet(@TempDir Path folder) {
		Path absent = folder.resolve("ainda-nao").resolve("existe.jpg");

		assertThat(keyOf(absent)).isEqualTo(keyOf(absent));
		assertThat(OperationPathKey.canonical(absent)).contains("existe.jpg");
	}

	@Test
	void handlesNonAsciiNames(@TempDir Path folder) throws Exception {
		Path accented = Files.createDirectory(folder.resolve("São Paulo — 2008"));

		assertThat(keyOf(accented)).isEqualTo(keyOf(folder.resolve("São Paulo — 2008")));
	}

	/**
	 * A file that exists is resolved directly, with no relative part to reattach -
	 * the other half of the walk that {@link #survivesAPathThatDoesNotExistYet}
	 * exercises.
	 */
	@Test
	void resolvesAnExistingFileWithoutReattachingAnything(@TempDir Path folder) throws Exception {
		Path file = Files.createFile(folder.resolve("foto.jpg"));

		assertThat(OperationPathKey.canonical(file)).endsWith("foto.jpg");
	}

	@Test
	void givesDifferentPathsDifferentKeys(@TempDir Path folder) {
		assertThat(keyOf(folder.resolve("a"))).isNotEqualTo(keyOf(folder.resolve("b")));
	}

	/**
	 * The ancestor is in the chain, so an operation claiming the ancestor collides
	 * - but held shared, so two operations under it do not collide with each
	 * other. Taking ancestors exclusively would serialise every operation on the
	 * volume, since they all share a root.
	 */
	@Test
	void holdsEveryAncestorSharedSoOnlyAnOperationOnTheAncestorItselfConflicts(@TempDir Path folder) {
		Path parent = folder.resolve("fotos");
		Path child = parent.resolve("2008").resolve("a.jpg");

		assertThat(OperationPathKey.chainOf(List.of(child))).contains(new PathLockKey(keyOf(parent), false));
	}

	@Test
	void holdsThePathItselfExclusively(@TempDir Path folder) {
		Path target = folder.resolve("fotos");

		assertThat(OperationPathKey.chainOf(List.of(target))).contains(new PathLockKey(keyOf(target), true));
	}

	/**
	 * When one requested path is an ancestor of another, the stronger claim wins:
	 * it is already held exclusively, so asking for it shared as well would be
	 * redundant - and would let the set disagree with itself.
	 */
	@Test
	void keepsTheExclusiveClaimWhenAPathIsAlsoAnAncestorOfAnother(@TempDir Path folder) {
		Path parent = folder.resolve("fotos");

		assertThat(OperationPathKey.chainOf(List.of(parent, parent.resolve("2008"))))
				.contains(new PathLockKey(keyOf(parent), true))
				.doesNotContain(new PathLockKey(keyOf(parent), false));
	}

	@Test
	void includesTheVolumeRootInTheChain(@TempDir Path folder) {
		Set<PathLockKey> chain = OperationPathKey.chainOf(List.of(folder.resolve("fotos")));

		assertThat(chain).contains(new PathLockKey(keyOf(folder.getRoot()), false));
	}

	/**
	 * The property that makes deadlock impossible rather than unlikely: the same
	 * two paths, requested in opposite orders, produce the same sequence of keys.
	 */
	@Test
	void ordersKeysIdenticallyRegardlessOfTheOrderTheyWereAskedFor(@TempDir Path folder) {
		Path source = folder.resolve("origem");
		Path target = folder.resolve("destino");

		assertThat(new ArrayList<>(OperationPathKey.chainOf(List.of(source, target))))
				.isEqualTo(new ArrayList<>(OperationPathKey.chainOf(List.of(target, source))));
	}

	@Test
	void ordersKeysAscendingSoEveryCallerAcquiresInOneSequence(@TempDir Path folder) {
		List<Long> keys = OperationPathKey.chainOf(List.of(folder.resolve("a"), folder.resolve("b"))).stream()
				.map(PathLockKey::key).toList();

		assertThat(keys).isSorted();
	}

	/**
	 * Two volumes in one operation - a move across drives - is just more keys in
	 * the same ordered set, with no special case anywhere.
	 */
	@Test
	void mergesPathsFromDifferentRootsIntoOneOrderedSet(@TempDir Path first, @TempDir Path second) {
		Set<PathLockKey> chain = OperationPathKey.chainOf(List.of(first.resolve("a"), second.resolve("b")));

		assertThat(chain).contains(new PathLockKey(keyOf(first.resolve("a")), true),
				new PathLockKey(keyOf(second.resolve("b")), true));
	}

	@Test
	void producesTheSameKeyForARelativePathPointingAtTheSameTarget(@TempDir Path folder) {
		Path absolute = folder.resolve("fotos");

		assertThat(keyOf(absolute)).isEqualTo(OperationPathKey.key(OperationPathKey.canonical(absolute)));
	}

	private long keyOf(Path path) {
		return OperationPathKey.key(OperationPathKey.canonical(path));
	}
}