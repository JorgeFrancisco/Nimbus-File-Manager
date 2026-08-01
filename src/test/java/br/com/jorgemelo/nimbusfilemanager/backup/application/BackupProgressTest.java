package br.com.jorgemelo.nimbusfilemanager.backup.application;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.backup.domain.enums.BackupPhase;

/**
 * What the screen shows while the catalog is being written or replaced.
 *
 * <p>
 * The phases are not decoration: emptying the catalog is the point of no
 * return, and a screen that goes quiet there is the one somebody closes the
 * browser on.
 */
class BackupProgressTest {

	private final BackupProgress progress = new BackupProgress();

	@Test
	void reportsNothingBeforeAnythingStarts() {
		Assertions.assertThat(progress.snapshot().phase()).isEqualTo(BackupPhase.IDLE);
		Assertions.assertThat(progress.snapshot().percent()).isNegative();
	}

	@Test
	void advancesAsTablesAreFinished() {
		progress.start(BackupPhase.EXPORTING, 4);
		progress.startTable("catalog_file");

		Assertions.assertThat(progress.snapshot().table()).isEqualTo("catalog_file");
		Assertions.assertThat(progress.snapshot().exporting()).isTrue();

		progress.finishTable();

		Assertions.assertThat(progress.snapshot().tablesDone()).isEqualTo(1);
		Assertions.assertThat(progress.snapshot().percent()).isEqualTo(25.0);
		Assertions.assertThat(progress.snapshot().table()).isNull();
	}

	/** Each phase has to be distinguishable, because they read differently. */
	@Test
	void tellsTheRestorePhasesApart() {
		progress.start(BackupPhase.CLEARING, 1);

		Assertions.assertThat(progress.snapshot().clearing()).isTrue();
		Assertions.assertThat(progress.snapshot().importing()).isFalse();

		progress.start(BackupPhase.IMPORTING, 1);

		Assertions.assertThat(progress.snapshot().importing()).isTrue();
		Assertions.assertThat(progress.snapshot().clearing()).isFalse();
	}

	/** Nothing to divide by is not zero percent - it is no percentage at all. */
	@Test
	void reportsNoPercentageWhenThereIsNothingToCount() {
		progress.start(BackupPhase.EXPORTING, 0);

		Assertions.assertThat(progress.snapshot().percent()).isNegative();
	}

	@Test
	void clearsWhatThePreviousRunLeftBehind() {
		progress.start(BackupPhase.EXPORTING, 2);
		progress.startTable("catalog_file");
		progress.finishTable();

		progress.reset();

		Assertions.assertThat(progress.snapshot().phase()).isEqualTo(BackupPhase.IDLE);
		Assertions.assertThat(progress.snapshot().tablesDone()).isZero();
		Assertions.assertThat(progress.snapshot().table()).isNull();
	}
}