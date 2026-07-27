package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MoveBaseline;

/**
 * The shared secure-move primitive: capture baseline -> create parents -> move
 * -> verify, plus a best-effort physical rollback. These cover the
 * safety-critical contract every relocating feature (organization, quarantine,
 * undo) depends on. The verifier is mocked so the move orchestration is
 * exercised in isolation over real files.
 */
class SecureFileMoveTest {

	private final SelfWrittenPathRegistry pathRegistry = new SelfWrittenPathRegistry(Clock.systemDefaultZone());
	private final OrganizationMoveVerifier verifier = mock(OrganizationMoveVerifier.class);
	private final SecureFileMove secureFileMove = new SecureFileMove(verifier, pathRegistry);

	@Test
	void moveRelocatesFileCreatingParentsAndVerifiesAgainstTheBaseline(@TempDir Path dir) throws IOException {
		Path source = Files.writeString(dir.resolve("source.txt"), "payload");
		Path target = dir.resolve("nested/deep/target.txt"); // parents do not exist yet

		when(verifier.capture(source)).thenReturn(new MoveBaseline(7L, "sha"));

		secureFileMove.move(source, target, false);

		assertThat(Files.exists(source)).isFalse();
		assertThat(target).hasContent("payload");

		verify(verifier).verify(source, target, new MoveBaseline(7L, "sha"));
	}

	/**
	 * Both ends of the move are announced to the watcher: the file leaving one
	 * folder and arriving in the other are the application rearranging its own
	 * library, and it updates the catalog itself. Without this the watcher answered
	 * each move with a full recursive inventory that could only rediscover what was
	 * already recorded.
	 */
	@Test
	void announcesBothEndsOfTheMoveSoTheWatcherDoesNotRescanTheLibrary(@TempDir Path dir) throws IOException {
		Path source = Files.writeString(dir.resolve("source.txt"), "payload");
		Path target = dir.resolve("target.txt");

		when(verifier.capture(source)).thenReturn(new MoveBaseline(7L, "sha"));

		secureFileMove.move(source, target, false);

		assertThat(pathRegistry.consume(source)).isTrue();
		assertThat(pathRegistry.consume(target)).isTrue();
	}

	@Test
	void moveWithoutOverwriteFailsWhenTargetExistsAndLeavesTheSourceIntact(@TempDir Path dir) throws IOException {
		Path source = Files.writeString(dir.resolve("source.txt"), "keep-me");
		Path target = Files.writeString(dir.resolve("target.txt"), "existing");

		when(verifier.capture(source)).thenReturn(new MoveBaseline(7L, "sha"));

		assertThatThrownBy(() -> secureFileMove.move(source, target, false))
				.isInstanceOf(FileAlreadyExistsException.class);

		// The source must never be lost when the move is refused.
		assertThat(source).hasContent("keep-me");
		assertThat(target).hasContent("existing");

		verify(verifier, never()).verify(any(), any(), any());
	}

	@Test
	void moveWithOverwriteReplacesTheExistingTarget(@TempDir Path dir) throws IOException {
		Path source = Files.writeString(dir.resolve("source.txt"), "new-bytes");
		Path target = Files.writeString(dir.resolve("target.txt"), "old-bytes");

		when(verifier.capture(source)).thenReturn(new MoveBaseline(9L, "sha"));

		secureFileMove.move(source, target, true);

		assertThat(Files.exists(source)).isFalse();
		assertThat(target).hasContent("new-bytes");
	}

	@Test
	void movePropagatesIntegrityFailureWithoutRollingBackItself(@TempDir Path dir) throws IOException {
		Path source = Files.writeString(dir.resolve("source.txt"), "payload");
		Path target = dir.resolve("target.txt");

		when(verifier.capture(source)).thenReturn(new MoveBaseline(7L, "sha"));
		doThrow(new MoveIntegrityException("corruption on move")).when(verifier).verify(any(), any(), any());

		assertThatThrownBy(() -> secureFileMove.move(source, target, false)).isInstanceOf(MoveIntegrityException.class);

		// Contract: move() does NOT roll back on a verify failure - the caller owns
		// that policy.
		// So the file has already moved and the source is gone.
		assertThat(Files.exists(source)).isFalse();
		assertThat(Files.exists(target)).isTrue();
	}

	@Test
	void rollbackMovesTheFileBackAndReportsSuccess(@TempDir Path dir) throws IOException {
		Path moved = Files.writeString(dir.resolve("moved.txt"), "payload");
		Path original = dir.resolve("original.txt");

		boolean rolledBack = secureFileMove.rollback(moved, original);

		assertThat(rolledBack).isTrue();
		assertThat(Files.exists(moved)).isFalse();
		assertThat(original).hasContent("payload");
	}

	@Test
	void rollbackReturnsFalseWhenItCannotMoveBack(@TempDir Path dir) {
		Path missing = dir.resolve("does-not-exist.txt");
		Path destination = dir.resolve("destination.txt");

		boolean rolledBack = secureFileMove.rollback(missing, destination);

		assertThat(rolledBack).isFalse();
		assertThat(Files.exists(destination)).isFalse();
	}
}