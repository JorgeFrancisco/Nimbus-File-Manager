package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * Clearing old executions forgets what happened; it never changes what the
 * library holds.
 *
 * <p>
 * A quarantined file lives on disk under a name nobody chose, and the movement
 * that put it there is the only record of where it came from. The execution
 * owns that movement by foreign key with {@code ON DELETE CASCADE}, so deleting
 * old executions used to empty the quarantine screen and leave the files
 * orphaned in the folder - the one action a user takes to tidy history quietly
 * destroying files they could still have restored.
 *
 * <p>
 * The guard is the same predicate the quarantine is listed and restored by, so
 * an item stops protecting its execution exactly when it stops being an item:
 * both the restore and the purge delete the movement row.
 */
class ExecutionRetentionQuarantineIntegrationTest extends SharedPostgresIntegrationTest {

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void anOldExecutionThatHoldsNothingIsForgotten() {
		long execution = finishedExecution();

		assertThat(executionRepository.deleteFinishedBefore(cutoff(), QuarantineConstants.QUARANTINED_REASONS))
				.isEqualTo(1);
		assertThat(exists(execution)).isFalse();
	}

	@Test
	void anExecutionHoldingAFileTheUserQuarantinedIsKept() {
		assertThat(survivesRetention(MovementReason.USER_QUARANTINED)).isTrue();
	}

	@Test
	void anExecutionHoldingADuplicateInQuarantineIsKept() {
		assertThat(survivesRetention(MovementReason.DUPLICATE_QUARANTINED)).isTrue();
	}

	@Test
	void anExecutionHoldingAConvertedOriginalInQuarantineIsKept() {
		assertThat(survivesRetention(MovementReason.CONVERTED_QUARANTINED)).isTrue();
	}

	/**
	 * The record is what protects, so removing it - which is what the purge does
	 * once the file is gone from the folder - hands the execution back to
	 * retention with no further bookkeeping.
	 */
	@Test
	void anExecutionIsForgottenOnceItsQuarantineStopsBeingOne() {
		long execution = finishedExecution();
		UUID movement = quarantined(execution, MovementReason.DUPLICATE_QUARANTINED);

		assertThat(executionRepository.deleteFinishedBefore(cutoff(), QuarantineConstants.QUARANTINED_REASONS))
				.isZero();

		jdbcTemplate.update("DELETE FROM movement WHERE movement_public_id = ?", movement);

		assertThat(executionRepository.deleteFinishedBefore(cutoff(), QuarantineConstants.QUARANTINED_REASONS))
				.isEqualTo(1);
		assertThat(exists(execution)).isFalse();
	}

	/**
	 * Keeping the last few is a request about how much history to show, and the
	 * count it satisfies is of executions - never a licence to delete one that is
	 * still holding a file.
	 */
	@Test
	void keepingOnlyTheLatestDoesNotSpendAProtectedExecutionToMeetTheCount() {
		long protectedExecution = finishedExecution();

		quarantined(protectedExecution, MovementReason.USER_QUARANTINED);

		long ordinary = finishedExecution();

		executionRepository.deleteFinishedNotIn(List.of(-1L), QuarantineConstants.QUARANTINED_REASONS);

		assertThat(exists(protectedExecution)).as("still holding a restorable file").isTrue();
		assertThat(exists(ordinary)).as("nothing depended on it").isFalse();
	}

	@Test
	void deletingEveryFinishedExecutionStillKeepsTheProtectedOnes() {
		long protectedExecution = finishedExecution();

		quarantined(protectedExecution, MovementReason.USER_QUARANTINED);

		long ordinary = finishedExecution();

		executionRepository.deleteAllFinished(QuarantineConstants.QUARANTINED_REASONS);

		assertThat(exists(protectedExecution)).isTrue();
		assertThat(exists(ordinary)).isFalse();
	}

	/**
	 * What the screen needs to offer the file back: the record itself, and the
	 * path it came from. Asserted after the cleanup ran, because a guard that
	 * kept the execution but let the movement go would leave the item listed and
	 * impossible to restore.
	 */
	@Test
	void theWayBackSurvivesAnAttemptToClearTheHistory() {
		long execution = finishedExecution();
		UUID movement = quarantined(execution, MovementReason.USER_QUARANTINED);

		executionRepository.deleteAllFinished(QuarantineConstants.QUARANTINED_REASONS);

		assertThat(jdbcTemplate.queryForObject(
				"SELECT requested_source_path FROM movement WHERE movement_public_id = ?", String.class, movement))
				.isEqualTo("D:\\library\\holiday.jpg");
		assertThat(listedInQuarantine(movement)).as("still on the quarantine screen").isEqualTo(1);
	}

	private boolean survivesRetention(MovementReason reason) {
		long execution = finishedExecution();

		quarantined(execution, reason);

		return executionRepository.deleteFinishedBefore(cutoff(), QuarantineConstants.QUARANTINED_REASONS) == 0
				&& exists(execution);
	}

	/** The predicate QuarantineListing reads the screen by. */
	private int listedInQuarantine(UUID movement) {
		return jdbcTemplate.queryForObject("""
				SELECT count(*) FROM movement
				 WHERE movement_public_id = ?
				   AND status = 'MOVED'
				   AND reason IN ('DUPLICATE_QUARANTINED', 'CONVERTED_QUARANTINED', 'USER_QUARANTINED')
				""", Integer.class, movement);
	}

	private LocalDateTime cutoff() {
		return LocalDateTime.now().plusDays(1);
	}

	private boolean exists(long execution) {
		return executionRepository.findById(execution).isPresent();
	}

	private long finishedExecution() {
		return jdbcTemplate.queryForObject("""
				INSERT INTO execution (execution_public_id, execution_type, status, created_at, available_at,
						started_at, finished_at)
				VALUES (gen_random_uuid(), 'EXPLORER_QUARANTINE', 'FINISHED', now(), now(), now(), now())
				RETURNING id
				""", Long.class);
	}

	private UUID quarantined(long execution, MovementReason reason) {
		UUID movement = UUID.randomUUID();

		CatalogFile file = catalogFileRepository.saveAndFlush(CatalogFile.builder().extension("jpg").sizeBytes(1L)
				.modifiedAt(Instant.now()).fileType(FileType.PHOTO).build());

		jdbcTemplate.update("""
				INSERT INTO movement (movement_public_id, execution_id, catalog_file_id, requested_source_path,
						requested_target_path, status, reason, moved_at, catalog_file_event_public_id, prepared_at)
				VALUES (?, ?, ?, 'D:\\library\\holiday.jpg', 'D:\\quarantine\\holiday.jpg', 'MOVED', ?, now(),
						gen_random_uuid(), now())
				""", movement, execution, file.getId(), reason.name());

		return movement;
	}
}