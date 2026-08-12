package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.FileSystemChange;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.constants.UsnReason;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.Interpretation;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.FileChangeKind;

class UsnChangeInterpreterTest {

	private static final Path ROOT = Path.of("/library").toAbsolutePath();

	/** What a file reference number is unique within. */
	private static final String VOLUME = "volume-under-test";
	private static final long SUB_A = 10L;
	private static final long SUB_B = 11L;
	private static final long OUTSIDE = 99L;

	private final Map<Long, Path> directories = new HashMap<>(
			Map.of(SUB_A, ROOT.resolve("a"), SUB_B, ROOT.resolve("b"), OUTSIDE, Path.of("/other").toAbsolutePath()));

	private final UsnPathResolver resolver = frn -> Optional.ofNullable(directories.get(frn));

	private UsnChangeInterpreter interpreter() {
		return new UsnChangeInterpreter(ROOT, VOLUME, resolver);
	}

	@Test
	void reportsAFileCreatedUnderTheRoot() {
		Interpretation result = interpreter()
				.interpret(List.of(usnRecord(1L, 100L, SUB_A, UsnReason.FILE_CREATE, false, "photo.jpg")));

		Assertions.assertThat(result.reconcileNeeded()).isFalse();
		Assertions.assertThat(result.changes()).extracting(FileSystemChange::path).containsExactly(ROOT.resolve("a").resolve("photo.jpg"));
	}

	@Test
	void ignoresChangesOutsideTheRoot() {
		Interpretation result = interpreter()
				.interpret(List.of(usnRecord(1L, 100L, OUTSIDE, UsnReason.FILE_CREATE, false, "x.jpg")));

		Assertions.assertThat(result.changes()).isEmpty();
	}

	@Test
	void reportsDeletesSoTheReconcileRemovesThem() {
		Interpretation result = interpreter()
				.interpret(List.of(usnRecord(1L, 100L, SUB_A, UsnReason.FILE_DELETE, false, "gone.jpg")));

		Assertions.assertThat(result.changes()).extracting(FileSystemChange::path).containsExactly(ROOT.resolve("a").resolve("gone.jpg"));
	}

	@Test
	void ignoresNonMaterialFileReasons() {
		Interpretation result = interpreter()
				.interpret(List.of(usnRecord(1L, 100L, SUB_A, UsnReason.CLOSE, false, "touched.jpg")));

		Assertions.assertThat(result.changes()).isEmpty();
	}

	@Test
	void renameWithinRootIsOneChangeCarryingBothOfItsEnds() {
		Interpretation result = interpreter()
				.interpret(List.of(usnRecord(1L, 100L, SUB_A, UsnReason.RENAME_OLD_NAME, false, "old.jpg"),
						usnRecord(2L, 100L, SUB_B, UsnReason.RENAME_NEW_NAME, false, "new.jpg")));

		Assertions.assertThat(result.reconcileNeeded()).isFalse();

		// One file moved once. Reported as two changes, the pair reads as a file that
		// vanished and an unrelated one that appeared - and the catalog would answer
		// that with a delete and a fresh identity instead of following the file.
		Assertions.assertThat(result.changes()).singleElement()
				.extracting(FileSystemChange::kind, FileSystemChange::previousPath, FileSystemChange::path)
				.containsExactly(FileChangeKind.RENAMED, ROOT.resolve("a").resolve("old.jpg"),
						ROOT.resolve("b").resolve("new.jpg"));
	}

	/**
	 * A file id nobody can scope to a volume names a different file on the next
	 * drive, so it is no identity at all - and a change carrying a wrong one is
	 * worse than one carrying none.
	 */
	@Test
	void anIdentityThatCannotBeScopedToAVolumeIsNotUsed() {
		Interpretation result = new UsnChangeInterpreter(ROOT, " ", resolver)
				.interpret(List.of(usnRecord(1L, 100L, SUB_A, UsnReason.FILE_CREATE, false, "photo.jpg")));

		Assertions.assertThat(result.changes()).singleElement().extracting(FileSystemChange::identity).isNull();
	}

	/**
	 * The journal names the folder a record is in, and a folder this pass cannot
	 * resolve is one outside the library - or one whose own record has not been
	 * read yet. Either way the change is about a path that cannot be named.
	 */
	@Test
	void aRecordWhoseFolderCannotBeResolvedNamesNoPath() {
		Interpretation result = interpreter()
				.interpret(List.of(usnRecord(1L, 100L, 12345L, UsnReason.FILE_CREATE, false, "photo.jpg"),
						usnRecord(2L, 101L, 12345L, UsnReason.FILE_DELETE, false, "gone.jpg")));

		Assertions.assertThat(result.changes()).isEmpty();
	}

	/**
	 * A rename pair whose new half cannot be resolved is a file that left: the old
	 * name is the last thing known about it, and saying nothing would leave the
	 * catalog pointing at a path nobody will visit again.
	 */
	@Test
	void aRenameWhoseDestinationCannotBeNamedIsADeparture() {
		Interpretation result = interpreter()
				.interpret(List.of(usnRecord(1L, 100L, SUB_A, UsnReason.RENAME_OLD_NAME, false, "leaving.jpg"),
						usnRecord(2L, 100L, 12345L, UsnReason.RENAME_NEW_NAME, false, "leaving.jpg")));

		Assertions.assertThat(result.changes()).singleElement()
				.extracting(FileSystemChange::kind, FileSystemChange::path)
				.containsExactly(FileChangeKind.DELETED, ROOT.resolve("a").resolve("leaving.jpg"));
	}

	@Test
	void moveOutOfRootReportsOnlyTheOldPath() {
		Interpretation result = interpreter()
				.interpret(List.of(usnRecord(1L, 100L, SUB_A, UsnReason.RENAME_OLD_NAME, false, "leaving.jpg"),
						usnRecord(2L, 100L, OUTSIDE, UsnReason.RENAME_NEW_NAME, false, "leaving.jpg")));

		Assertions.assertThat(result.changes()).extracting(FileSystemChange::path).containsExactly(ROOT.resolve("a").resolve("leaving.jpg"));
	}

	@Test
	void moveIntoRootReportsOnlyTheNewPath() {
		Interpretation result = interpreter()
				.interpret(List.of(usnRecord(1L, 100L, OUTSIDE, UsnReason.RENAME_OLD_NAME, false, "arriving.jpg"),
						usnRecord(2L, 100L, SUB_B, UsnReason.RENAME_NEW_NAME, false, "arriving.jpg")));

		Assertions.assertThat(result.changes()).extracting(FileSystemChange::path).containsExactly(ROOT.resolve("b").resolve("arriving.jpg"));
	}

	@Test
	void unpairedOldNameStillReportsTheFileLeftBehind() {
		Interpretation result = interpreter()
				.interpret(List.of(usnRecord(1L, 100L, SUB_A, UsnReason.RENAME_OLD_NAME, false, "moved.jpg")));

		Assertions.assertThat(result.changes()).extracting(FileSystemChange::path).containsExactly(ROOT.resolve("a").resolve("moved.jpg"));
	}

	/**
	 * A moved directory takes its whole subtree with it and the descendants emit
	 * no records of their own, so both halves are needed: the change itself, which
	 * is what a bulk relocation acts on, and the reconcile that covers everything
	 * underneath it until that path is wired.
	 */
	@Test
	void directoryMoveIsReportedAndStillAsksForAReconcile() {
		Interpretation result = interpreter()
				.interpret(List.of(usnRecord(1L, 500L, SUB_A, UsnReason.RENAME_OLD_NAME, true, "2023"),
						usnRecord(2L, 500L, SUB_B, UsnReason.RENAME_NEW_NAME, true, "2024")));

		Assertions.assertThat(result.reconcileNeeded()).isTrue();

		Assertions.assertThat(result.changes()).singleElement()
				.extracting(FileSystemChange::kind, FileSystemChange::directory, FileSystemChange::previousPath,
						FileSystemChange::path)
				.containsExactly(FileChangeKind.RENAMED, true, ROOT.resolve("a").resolve("2023"),
						ROOT.resolve("b").resolve("2024"));
	}

	@Test
	void directoryCreateAndDeleteNeedNoAction() {
		Interpretation result = interpreter()
				.interpret(List.of(usnRecord(1L, 500L, SUB_A, UsnReason.FILE_CREATE, true, "newdir"),
						usnRecord(2L, 501L, SUB_A, UsnReason.FILE_DELETE, true, "olddir")));

		Assertions.assertThat(result.reconcileNeeded()).isFalse();
		Assertions.assertThat(result.changes()).isEmpty();
	}

	private static UsnRecord usnRecord(long usn, long frn, long parentFrn, int reason, boolean directory, String name) {
		long attributes = directory ? 0x10L : 0x80L;

		// No timestamp: what these are about is which path a record names, and the
		// journal's own clock belongs to the checks that read it.
		return new UsnRecord(usn, frn, parentFrn, reason, attributes, name, 0L);
	}

	/**
	 * A folder that left this batch under its old name may have taken any number of
	 * files with it, and the journal does not list them: only a reconcile can tell
	 * what moved.
	 */
	@Test
	void aDirectoryThatLeftUnderItsOldNameAsksForAReconcile() {
		Interpretation result = interpreter()
				.interpret(List.of(usnRecord(1L, 100L, SUB_A, UsnReason.RENAME_OLD_NAME, true, "trip")));

		Assertions.assertThat(result.reconcileNeeded()).isTrue();
	}
}