package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreOptions;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestorePlan;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.ConflictResolution;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.RestoreOutcome;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * The conversation about restoring one file, which is what has to happen while
 * there is still somebody to answer it.
 *
 * <p>
 * Every test here asserts one of two things: the question to put back to the
 * person, or the destination the restore will use. Nothing in between - a plan
 * that reached the queue with a choice still open would be a worker deciding on
 * somebody's behalf.
 */
class QuarantineRestorePlannerTest {

	private final MovementRepository movementRepository = mock(MovementRepository.class);
	private final QuarantineRestorePlanner planner = new QuarantineRestorePlanner(movementRepository);

	@Test
	void plansTheMoveBackToTheOriginWhenTheDestinationIsFree(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg");

		Movement movement = known(origin.resolve("a.jpg"), quarantine);

		QuarantineRestorePlan plan = planner.plan(movement.getMovementPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(plan.decided()).isTrue();
		Assertions.assertThat(plan.quarantined()).isEqualTo(quarantine);
		Assertions.assertThat(plan.destination()).isEqualTo(origin.resolve("a.jpg"));
	}

	@Test
	void nullOptionsFallBackToTheSafeDefaults(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));

		Movement movement = known(origin.resolve("a.jpg"), writeQuarantineCopy(tmp, "10__a.jpg"));

		QuarantineRestorePlan plan = planner.plan(movement.getMovementPublicId(), null);

		Assertions.assertThat(plan.decided()).isTrue();
		Assertions.assertThat(plan.destination()).isEqualTo(origin.resolve("a.jpg"));
	}

	/** A name collision is a question, and a question queues nothing. */
	@Test
	void answersWithTheCollisionWhenTheDestinationIsTaken(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));

		Files.writeString(origin.resolve("a.jpg"), "existing");

		Movement movement = known(origin.resolve("a.jpg"), writeQuarantineCopy(tmp, "10__a.jpg"));

		QuarantineRestorePlan plan = planner.plan(movement.getMovementPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(plan.decided()).isFalse();
		Assertions.assertThat(plan.answer().outcome()).isEqualTo(RestoreOutcome.CONFLICT.name());
		Assertions.assertThat(plan.answer().success()).isFalse();
	}

	/**
	 * Renaming is the person's answer to that collision, so the new name is chosen
	 * here - never by the worker, which would be picking one on their behalf.
	 */
	@Test
	void picksTheNewNameWhenTheAnswerIsToRename(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));

		Files.writeString(origin.resolve("a.jpg"), "existing");

		Movement movement = known(origin.resolve("a.jpg"), writeQuarantineCopy(tmp, "10__a.jpg"));

		QuarantineRestorePlan plan = planner.plan(movement.getMovementPublicId(),
				new QuarantineRestoreOptions(null, ConflictResolution.RENAME));

		Assertions.assertThat(plan.decided()).isTrue();
		Assertions.assertThat(plan.destination()).isEqualTo(origin.resolve("a (1).jpg"));
	}

	/**
	 * An extension-less name must not lose its whole filename to the "(1)" suffix
	 * logic, which splits on the last dot.
	 */
	@Test
	void renamingHandlesANameWithoutAnExtension(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));

		Files.writeString(origin.resolve("README"), "existing");

		Movement movement = known(origin.resolve("README"), writeQuarantineCopy(tmp, "10__README"));

		QuarantineRestorePlan plan = planner.plan(movement.getMovementPublicId(),
				new QuarantineRestoreOptions(null, ConflictResolution.RENAME));

		Assertions.assertThat(plan.destination()).isEqualTo(origin.resolve("README (1)"));
	}

	@Test
	void plansIntoTheChosenAlternateFolder(@TempDir Path tmp) throws Exception {
		Path alternate = Files.createDirectories(tmp.resolve("elsewhere"));

		Movement movement = known(tmp.resolve("gone").resolve("a.jpg"), writeQuarantineCopy(tmp, "10__a.jpg"));

		QuarantineRestorePlan plan = planner.plan(movement.getMovementPublicId(),
				new QuarantineRestoreOptions(alternate, ConflictResolution.BLOCK));

		Assertions.assertThat(plan.decided()).isTrue();
		Assertions.assertThat(plan.destination()).isEqualTo(alternate.resolve("a.jpg"));
	}

	@Test
	void answersWithOriginMissingWhenTheOriginalFolderIsGone(@TempDir Path tmp) throws Exception {
		Movement movement = known(tmp.resolve("gone").resolve("a.jpg"), writeQuarantineCopy(tmp, "10__a.jpg"));

		QuarantineRestorePlan plan = planner.plan(movement.getMovementPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(plan.answer().outcome()).isEqualTo(RestoreOutcome.ORIGIN_MISSING.name());
	}

	@Test
	void refusesARecordedOriginalPathWithNoFolderAboveIt(@TempDir Path tmp) throws Exception {
		Movement movement = known(tmp.getRoot(), writeQuarantineCopy(tmp, "10__a.jpg"));

		QuarantineRestorePlan plan = planner.plan(movement.getMovementPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(plan.answer().outcome()).isEqualTo(RestoreOutcome.ERROR.name());
		Assertions.assertThat(plan.answer().message()).isNotBlank();
	}

	@Test
	void keepsTheFileInQuarantineWhenTheAnswerIsToSkip(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));

		Movement movement = known(origin.resolve("a.jpg"), writeQuarantineCopy(tmp, "10__a.jpg"));

		QuarantineRestorePlan plan = planner.plan(movement.getMovementPublicId(),
				new QuarantineRestoreOptions(null, ConflictResolution.SKIP));

		Assertions.assertThat(plan.decided()).isFalse();
		Assertions.assertThat(plan.answer().outcome()).isEqualTo(RestoreOutcome.SKIPPED.name());
	}

	@Test
	void answersWithMissingWhenTheQuarantineCopyIsGone(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));

		Movement movement = known(origin.resolve("a.jpg"), tmp.resolve("trash").resolve("exec-1").resolve("10__a.jpg"));

		QuarantineRestorePlan plan = planner.plan(movement.getMovementPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(plan.answer().outcome()).isEqualTo(RestoreOutcome.MISSING_IN_QUARANTINE.name());
	}

	/**
	 * The same rule as the forward path: never follow a symlink/junction/.lnk. If
	 * the quarantine copy was swapped for a link, the restore is refused instead of
	 * putting the link into the library.
	 */
	@Test
	void refusesAQuarantineCopyThatIsNotAPhysicalFile(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));

		Movement movement = known(origin.resolve("a.lnk"), writeQuarantineCopy(tmp, "10__a.lnk"));

		QuarantineRestorePlan plan = planner.plan(movement.getMovementPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(plan.answer().outcome()).isEqualTo(RestoreOutcome.ERROR.name());
	}

	@Test
	void answersWithAnErrorForAnIdThatNamesNoMovement() {
		UUID unknown = UUID.randomUUID();

		when(movementRepository.findByMovementPublicId(unknown)).thenReturn(Optional.empty());

		QuarantineRestorePlan plan = planner.plan(unknown, QuarantineRestoreOptions.defaults());

		Assertions.assertThat(plan.decided()).isFalse();
		Assertions.assertThat(plan.answer().outcome()).isEqualTo(RestoreOutcome.ERROR.name());
		Assertions.assertThat(plan.answer().movementId()).isEqualTo(unknown);
	}

	@Test
	void refusesAMovementThatIsNoLongerQuarantined(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));

		Movement undone = known(origin.resolve("a.jpg"), writeQuarantineCopy(tmp, "10__a.jpg"));

		undone.setStatus(MovementStatus.UNDONE);

		Movement organized = known(origin.resolve("b.jpg"), writeQuarantineCopy(tmp, "11__b.jpg"));

		organized.setReason(MovementReason.NONE);

		QuarantineRestorePlan undonePlan = planner.plan(undone.getMovementPublicId(), QuarantineRestoreOptions.defaults());

		QuarantineRestorePlan organizedPlan = planner.plan(organized.getMovementPublicId(),
				QuarantineRestoreOptions.defaults());

		Assertions.assertThat(undonePlan.answer().outcome()).isEqualTo(RestoreOutcome.ERROR.name());
		Assertions.assertThat(organizedPlan.answer().outcome()).isEqualTo(RestoreOutcome.ERROR.name());
	}

	private Path writeQuarantineCopy(Path tmp, String name) throws Exception {
		Path folder = Files.createDirectories(tmp.resolve("trash").resolve("exec-1"));

		return Files.writeString(folder.resolve(name), "content");
	}

	private Movement known(Path original, Path quarantine) {
		Movement movement = Movement.builder().movementPublicId(UUID.randomUUID()).requestedSourcePath(PathUtils.normalize(original))
				.requestedTargetPath(PathUtils.normalize(quarantine)).status(MovementStatus.MOVED)
				.reason(MovementReason.DUPLICATE_QUARANTINED).movedAt(Instant.now()).build();

		when(movementRepository.findByMovementPublicId(movement.getMovementPublicId())).thenReturn(Optional.of(movement));

		return movement;
	}
}