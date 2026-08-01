package br.com.jorgemelo.nimbusfilemanager.backup.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.backup.domain.enums.BackupPhase;

/**
 * What the screen shows while the catalog is being written or replaced.
 *
 * <p>
 * The size is read from the file at the moment the screen asks, because the
 * work happens inside one external command that reports nothing until it
 * exits - the file growing on disk is the only honest signal there is.
 */
class BackupProgressTest {

	private final BackupProgress progress = new BackupProgress();

	@Test
	void reportsNothingBeforeAnythingStarts() {
		Assertions.assertThat(progress.snapshot().phase()).isEqualTo(BackupPhase.IDLE);
		Assertions.assertThat(progress.snapshot().bytes()).isZero();
	}

	@Test
	void reportsHowMuchOfTheFileExistsSoFar(@TempDir Path folder) throws IOException {
		Path file = folder.resolve("catalog.dump");

		progress.start(BackupPhase.EXPORTING, file);

		Assertions.assertThat(progress.snapshot().exporting()).isTrue();
		Assertions.assertThat(progress.snapshot().bytes()).isZero();

		Files.write(file, new byte[3 * 1048576]);

		Assertions.assertThat(progress.snapshot().megabytes()).isEqualTo(3);
	}

	/**
	 * The two phases read differently on screen: one is being written, the other
	 * is replacing the catalog and cannot be stopped.
	 */
	@Test
	void tellsTheTwoPhasesApart(@TempDir Path folder) {
		progress.start(BackupPhase.IMPORTING, folder.resolve("catalog.dump"));

		Assertions.assertThat(progress.snapshot().importing()).isTrue();
		Assertions.assertThat(progress.snapshot().exporting()).isFalse();
	}

	/** A file that is not there yet answers zero rather than failing the screen. */
	@Test
	void answersZeroWhileTheFileDoesNotExist(@TempDir Path folder) {
		progress.start(BackupPhase.EXPORTING, folder.resolve("not-written-yet.dump"));

		Assertions.assertThat(progress.snapshot().bytes()).isZero();
	}

	@Test
	void clearsWhatThePreviousRunLeftBehind(@TempDir Path folder) throws IOException {
		Path file = Files.writeString(folder.resolve("catalog.dump"), "content");

		progress.start(BackupPhase.EXPORTING, file);
		progress.reset();

		Assertions.assertThat(progress.snapshot().phase()).isEqualTo(BackupPhase.IDLE);
		Assertions.assertThat(progress.snapshot().bytes()).isZero();
	}
}