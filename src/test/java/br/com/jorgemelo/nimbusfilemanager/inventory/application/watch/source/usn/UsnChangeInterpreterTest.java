package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.constants.UsnReason;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.Interpretation;

class UsnChangeInterpreterTest {

	private static final Path ROOT = Path.of("/library").toAbsolutePath();
	private static final long SUB_A = 10L;
	private static final long SUB_B = 11L;
	private static final long OUTSIDE = 99L;

	private final Map<Long, Path> directories = new HashMap<>(
			Map.of(SUB_A, ROOT.resolve("a"), SUB_B, ROOT.resolve("b"), OUTSIDE, Path.of("/other").toAbsolutePath()));

	private final UsnPathResolver resolver = frn -> Optional.ofNullable(directories.get(frn));

	private UsnChangeInterpreter interpreter() {
		return new UsnChangeInterpreter(ROOT, resolver);
	}

	@Test
	void reportsAFileCreatedUnderTheRoot() {
		Interpretation result = interpreter()
				.interpret(List.of(usnRecord(1L, 100L, SUB_A, UsnReason.FILE_CREATE, false, "photo.jpg")));

		Assertions.assertThat(result.reconcileNeeded()).isFalse();
		Assertions.assertThat(result.changedFiles()).containsExactly(ROOT.resolve("a").resolve("photo.jpg"));
	}

	@Test
	void ignoresChangesOutsideTheRoot() {
		Interpretation result = interpreter()
				.interpret(List.of(usnRecord(1L, 100L, OUTSIDE, UsnReason.FILE_CREATE, false, "x.jpg")));

		Assertions.assertThat(result.changedFiles()).isEmpty();
	}

	@Test
	void reportsDeletesSoTheReconcileRemovesThem() {
		Interpretation result = interpreter()
				.interpret(List.of(usnRecord(1L, 100L, SUB_A, UsnReason.FILE_DELETE, false, "gone.jpg")));

		Assertions.assertThat(result.changedFiles()).containsExactly(ROOT.resolve("a").resolve("gone.jpg"));
	}

	@Test
	void ignoresNonMaterialFileReasons() {
		Interpretation result = interpreter()
				.interpret(List.of(usnRecord(1L, 100L, SUB_A, UsnReason.CLOSE, false, "touched.jpg")));

		Assertions.assertThat(result.changedFiles()).isEmpty();
	}

	@Test
	void renameWithinRootReportsBothOldAndNewPaths() {
		Interpretation result = interpreter()
				.interpret(List.of(usnRecord(1L, 100L, SUB_A, UsnReason.RENAME_OLD_NAME, false, "old.jpg"),
						usnRecord(2L, 100L, SUB_B, UsnReason.RENAME_NEW_NAME, false, "new.jpg")));

		Assertions.assertThat(result.reconcileNeeded()).isFalse();
		Assertions.assertThat(result.changedFiles()).containsExactlyInAnyOrder(ROOT.resolve("a").resolve("old.jpg"),
				ROOT.resolve("b").resolve("new.jpg"));
	}

	@Test
	void moveOutOfRootReportsOnlyTheOldPath() {
		Interpretation result = interpreter()
				.interpret(List.of(usnRecord(1L, 100L, SUB_A, UsnReason.RENAME_OLD_NAME, false, "leaving.jpg"),
						usnRecord(2L, 100L, OUTSIDE, UsnReason.RENAME_NEW_NAME, false, "leaving.jpg")));

		Assertions.assertThat(result.changedFiles()).containsExactly(ROOT.resolve("a").resolve("leaving.jpg"));
	}

	@Test
	void moveIntoRootReportsOnlyTheNewPath() {
		Interpretation result = interpreter()
				.interpret(List.of(usnRecord(1L, 100L, OUTSIDE, UsnReason.RENAME_OLD_NAME, false, "arriving.jpg"),
						usnRecord(2L, 100L, SUB_B, UsnReason.RENAME_NEW_NAME, false, "arriving.jpg")));

		Assertions.assertThat(result.changedFiles()).containsExactly(ROOT.resolve("b").resolve("arriving.jpg"));
	}

	@Test
	void unpairedOldNameStillReportsTheFileLeftBehind() {
		Interpretation result = interpreter()
				.interpret(List.of(usnRecord(1L, 100L, SUB_A, UsnReason.RENAME_OLD_NAME, false, "moved.jpg")));

		Assertions.assertThat(result.changedFiles()).containsExactly(ROOT.resolve("a").resolve("moved.jpg"));
	}

	@Test
	void directoryMoveRequestsAReconcileInsteadOfFileEvents() {
		Interpretation result = interpreter()
				.interpret(List.of(usnRecord(1L, 500L, SUB_A, UsnReason.RENAME_OLD_NAME, true, "2023"),
						usnRecord(2L, 500L, SUB_B, UsnReason.RENAME_NEW_NAME, true, "2024")));

		Assertions.assertThat(result.reconcileNeeded()).isTrue();
		Assertions.assertThat(result.changedFiles()).isEmpty();
	}

	@Test
	void directoryCreateAndDeleteNeedNoAction() {
		Interpretation result = interpreter()
				.interpret(List.of(usnRecord(1L, 500L, SUB_A, UsnReason.FILE_CREATE, true, "newdir"),
						usnRecord(2L, 501L, SUB_A, UsnReason.FILE_DELETE, true, "olddir")));

		Assertions.assertThat(result.reconcileNeeded()).isFalse();
		Assertions.assertThat(result.changedFiles()).isEmpty();
	}

	private static UsnRecord usnRecord(long usn, long frn, long parentFrn, int reason, boolean directory, String name) {
		long attributes = directory ? 0x10L : 0x80L;

		return new UsnRecord(usn, frn, parentFrn, reason, attributes, name);
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