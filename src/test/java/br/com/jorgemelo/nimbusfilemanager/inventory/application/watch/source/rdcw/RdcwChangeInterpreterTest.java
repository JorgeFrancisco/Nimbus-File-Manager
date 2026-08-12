package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw;

import java.nio.file.Path;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.FileSystemChange;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.FileChangeKind;

class RdcwChangeInterpreterTest {

	private static final Path ROOT = Path.of("/library").toAbsolutePath();

	/** What a file id is unique within; the next volume reuses the same numbers. */
	private static final String VOLUME = "volume-under-test";

	private final RdcwChangeInterpreter interpreter = new RdcwChangeInterpreter(ROOT, VOLUME);

	@Test
	void resolvesBackSlashRelativePathsUnderTheRoot() {
		List<FileSystemChange> changed = interpreter
				.interpret(List.of(Notifications.modified("2024\\05\\photo.jpg"), Notifications.modified("top.jpg")));

		Assertions.assertThat(changed).extracting(FileSystemChange::path).containsExactly(
				ROOT.resolve("2024").resolve("05").resolve("photo.jpg"), ROOT.resolve("top.jpg"));
	}

	@Test
	void deduplicatesRepeatedPathsWithinABatch() {
		List<FileSystemChange> changed = interpreter
				.interpret(List.of(Notifications.modified("a\\b.jpg"), Notifications.modified("a\\b.jpg")));

		Assertions.assertThat(changed).extracting(FileSystemChange::path)
				.containsExactly(ROOT.resolve("a").resolve("b.jpg"));
	}

	@Test
	void dropsPathsThatEscapeTheRoot() {
		Assertions.assertThat(interpreter.interpret(List.of(Notifications.modified("..\\..\\outside.jpg")))).isEmpty();
	}

	@Test
	void tellsWhatArrivedFromWhatLeftAndFromWhatWasWrittenTo() {
		List<FileSystemChange> changed = interpreter.interpret(List.of(Notifications.added("new.jpg"),
				Notifications.removed("gone.jpg"), Notifications.modified("edited.jpg")));

		Assertions.assertThat(changed).extracting(FileSystemChange::kind).containsExactly(FileChangeKind.CREATED,
				FileChangeKind.DELETED, FileChangeKind.MODIFIED);
	}

	/** Both names in one change, which is what a rename is. */
	@Test
	void aRenameWithinTheRootCarriesBothOfItsNames() {
		List<FileSystemChange> changed = interpreter.interpret(Notifications.renamed(500L, "old.jpg", "new.jpg"));

		Assertions.assertThat(changed).singleElement()
				.extracting(FileSystemChange::kind, FileSystemChange::previousPath, FileSystemChange::path)
				.containsExactly(FileChangeKind.RENAMED, ROOT.resolve("old.jpg"), ROOT.resolve("new.jpg"));
	}

	/**
	 * Only half the pair is under the root, and the half that is decides what was
	 * observed: a file that arrived from outside, or one that left.
	 */
	@Test
	void aRenameAcrossTheBoundaryIsAnArrivalOrADeparture() {
		Assertions.assertThat(interpreter.interpret(Notifications.renamed(500L, "..\\outside.jpg", "arrived.jpg")))
				.singleElement().extracting(FileSystemChange::kind, FileSystemChange::path)
				.containsExactly(FileChangeKind.CREATED, ROOT.resolve("arrived.jpg"));

		Assertions.assertThat(interpreter.interpret(Notifications.renamed(501L, "leaving.jpg", "..\\outside.jpg")))
				.singleElement().extracting(FileSystemChange::kind, FileSystemChange::path)
				.containsExactly(FileChangeKind.DELETED, ROOT.resolve("leaving.jpg"));
	}

	/**
	 * A file id nobody can scope names a different file on the next drive, so it is
	 * no identity at all - and a change carrying a wrong one is worse than one
	 * carrying none.
	 */
	@Test
	void anIdentityThatCannotBeScopedToAVolumeIsNotUsed() {
		List<FileSystemChange> changed = new RdcwChangeInterpreter(ROOT, " ")
				.interpret(List.of(Notifications.modified("photo.jpg")));

		Assertions.assertThat(changed).singleElement().extracting(FileSystemChange::identity).isNull();
	}

	@Test
	void aFolderIsReportedAsOne() {
		Assertions.assertThat(interpreter.interpret(Notifications.renamedDirectory(500L, "2023", "2024")))
				.singleElement().extracting(FileSystemChange::directory).isEqualTo(true);
	}
}