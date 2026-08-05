package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MoveBaseline;
import br.com.jorgemelo.nimbusfilemanager.shared.application.InMemorySelfWrittenPaths;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The shared secure-move primitive: capture baseline -> create parents -> move
 * -> verify, plus a best-effort physical rollback. These cover the
 * safety-critical contract every relocating feature (organization, quarantine,
 * undo) depends on. The verifier is mocked so the move orchestration is
 * exercised in isolation over real files.
 */
class SecureFileMoveTest {

	private final SelfWrittenPathRegistry pathRegistry = new SelfWrittenPathRegistry(new InMemorySelfWrittenPaths(),
			Clock.systemDefaultZone());
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

		assertThat(pathRegistry.announcedAmong(List.of(source, target))).containsExactlyInAnyOrder(source, target);
	}

	/**
	 * Twice a conversion ended with the encoded video back under its temporary name
	 * and the catalogued path empty, with nothing in the log to say when: the move
	 * had reported success and no one looked again. Here the file vanishes right
	 * after the move - the same shape as the incident - and the move has to say so.
	 */
	@Test
	void warnsWhenTheFileIsNotAtTheTargetOnceTheMoveReturned(@TempDir Path dir) throws IOException {
		Path source = Files.writeString(dir.resolve("source.txt"), "payload");
		Path target = dir.resolve("target.txt");

		when(verifier.capture(source)).thenReturn(new MoveBaseline(7L, "sha"));

		// Something outside the application takes the file away between the move and
		// the check, which is exactly what the log has to catch.
		doAnswer(_ -> {
			Files.delete(target);

			return null;
		}).when(verifier).verify(any(), any(), any());

		List<ILoggingEvent> events = logsWhileMoving(source, target);

		assertThat(events).anyMatch(event -> event.getLevel() == Level.WARN
				&& event.getFormattedMessage().contains("the file system disagrees"));
	}

	/**
	 * The other half of the same incident: in both cases the encoded video was
	 * still sitting under its temporary name after a move that reported success. A
	 * source that survives its own move is the fact the investigations lacked.
	 */
	@Test
	void warnsWhenTheSourceIsStillThereOnceTheMoveReturned(@TempDir Path dir) throws IOException {
		Path source = Files.writeString(dir.resolve("source.txt"), "payload");
		Path target = dir.resolve("target.txt");

		when(verifier.capture(source)).thenReturn(new MoveBaseline(7L, "sha"));

		doAnswer(_ -> Files.writeString(source, "payload")).when(verifier).verify(any(), any(), any());

		List<ILoggingEvent> events = logsWhileMoving(source, target);

		assertThat(events).anyMatch(
				event -> event.getLevel() == Level.WARN && event.getFormattedMessage().contains("stillThere=true"));
	}

	/** A move that landed says nothing: the check is silent when all is well. */
	@Test
	void staysQuietWhenTheMoveLandedWhereItShould(@TempDir Path dir) throws IOException {
		Path source = Files.writeString(dir.resolve("source.txt"), "payload");
		Path target = dir.resolve("target.txt");

		when(verifier.capture(source)).thenReturn(new MoveBaseline(7L, "sha"));

		List<ILoggingEvent> events = logsWhileMoving(source, target);

		assertThat(events).noneMatch(event -> event.getLevel() == Level.WARN);
	}

	private List<ILoggingEvent> logsWhileMoving(Path source, Path target) throws IOException {
		Logger logger = (Logger) LoggerFactory.getLogger(SecureFileMove.class);

		ListAppender<ILoggingEvent> appender = new ListAppender<>();

		appender.start();

		logger.addAppender(appender);

		try {
			secureFileMove.move(source, target, false);
		} finally {
			logger.detachAppender(appender);
		}

		return appender.list;
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

	/**
	 * A file held open by another process fails while the baseline is being read,
	 * and the hash service reports that as an {@code IllegalStateException} - which
	 * this method does not declare. Callers that catch {@code IOException} (the
	 * rename on the Files screen) saw it escape as an error page, so it is
	 * translated back into what the signature promises.
	 */
	@Test
	void reportsAnUnreadableSourceAsAnIoFailureSoCallersCanCatchIt(@TempDir Path dir) throws IOException {
		Path source = Files.writeString(dir.resolve("in-use.txt"), "payload");

		Path target = dir.resolve("target.txt");

		when(verifier.capture(source)).thenThrow(
				new IllegalStateException("Could not read file", new IOException("locked by another process")));

		assertThatThrownBy(() -> secureFileMove.move(source, target, false))
				.isInstanceOf(IOException.class).hasRootCauseMessage("locked by another process");

		assertThat(Files.exists(source)).isTrue();
	}

	/**
	 * The translation above is for I/O and nothing else: a state failure with no
	 * I/O behind it is a defect, and dressing it as an {@code IOException} would
	 * send it to the branch that reports "the file is in use" to the user.
	 */
	@Test
	void leavesAFailureThatIsNotAboutIoAsItIs(@TempDir Path dir) throws IOException {
		Path source = Files.writeString(dir.resolve("source.txt"), "payload");

		Path target = dir.resolve("target.txt");

		when(verifier.capture(source)).thenThrow(new IllegalStateException("Hash algorithm not available: SHA-256"));

		assertThatThrownBy(() -> secureFileMove.move(source, target, false))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("Hash algorithm");
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