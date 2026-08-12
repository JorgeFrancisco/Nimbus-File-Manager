package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.MovementRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PreparedMovement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;

/**
 * The operation exists before the file system does, and a second attempt finds
 * it.
 *
 * <p>
 * That is the whole point of preparing, and it cannot be shown against mocks:
 * what makes a retry safe is a unique constraint arbitrating between two
 * attempts, and what makes it correct is that both then read back the same two
 * identities.
 */
class MovementWriterIntegrationTest extends SharedPostgresIntegrationTest {

	@Autowired
	private MovementWriter movementWriter;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@TempDir
	private Path library;

	@Test
	void preparingWritesOnePendingOperationPerFile() {
		long execution = execution();
		long first = catalogued();
		long second = catalogued();

		List<PreparedMovement> prepared = movementWriter.prepare(execution, List.of(request(first), request(second)));

		Assertions.assertThat(prepared).hasSize(2)
				.allSatisfy(movement -> Assertions.assertThat(movement.status()).isEqualTo(MovementStatus.PENDING));
		Assertions.assertThat(prepared).extracting(PreparedMovement::movementPublicId).doesNotContainNull();
		Assertions.assertThat(prepared).extracting(PreparedMovement::catalogFileEventPublicId).doesNotContainNull();
	}

	/** Prepared means recorded, whatever the caller does with the answer. */
	@Test
	void aPreparedOperationIsPendingWithNoMoveRecorded() {
		long execution = execution();
		long file = catalogued();

		movementWriter.prepare(execution, List.of(request(file)));

		Assertions.assertThat(statusOf(execution, file)).isEqualTo("PENDING");
		Assertions.assertThat(movedAtOf(execution, file)).isNull();
		Assertions.assertThat(preparedAtOf(execution, file)).isNotNull();
	}

	/**
	 * The property a retry depends on. A second attempt writes nothing and reads
	 * back what the first attempt decided - which is what lets it record the same
	 * fact rather than a second one.
	 */
	@Test
	void preparingTwiceKeepsTheIdentitiesTheFirstAttemptChose() {
		long execution = execution();
		long file = catalogued();

		PreparedMovement first = movementWriter.prepare(execution, List.of(request(file))).getFirst();
		PreparedMovement second = movementWriter.prepare(execution, List.of(request(file))).getFirst();

		Assertions.assertThat(second.movementPublicId()).isEqualTo(first.movementPublicId());
		Assertions.assertThat(second.catalogFileEventPublicId()).isEqualTo(first.catalogFileEventPublicId());
		Assertions.assertThat(second.id()).isEqualTo(first.id());
		Assertions.assertThat(rowsFor(execution)).isEqualTo(1);
	}

	/**
	 * What a retry asks when it can no longer work out what it was doing.
	 *
	 * <p>
	 * The reading above needs the files to ask about, and after the first
	 * attempt's effect the catalog no longer names them - which is exactly when a
	 * retry needs them most. This asks the operations themselves, and answers for
	 * one run only: another execution's work is not this one's to finish.
	 */
	@Test
	void theOperationsOneRunReservedAreReadBackWithoutNamingTheFiles() {
		long execution = execution();
		long first = catalogued();
		long second = catalogued();

		List<PreparedMovement> prepared = movementWriter.prepare(execution,
				List.of(request(first), request(second)));

		movementWriter.markMoved(execution, List.of(prepared.getFirst().movementPublicId()));

		List<PreparedMovement> reserved = movementWriter.reserved(execution);

		Assertions.assertThat(reserved).extracting(PreparedMovement::movementPublicId)
				.containsExactlyElementsOf(prepared.stream().map(PreparedMovement::movementPublicId).toList());

		// Each one as it now stands, which is what tells a retry what is left to do.
		Assertions.assertThat(reserved).extracting(PreparedMovement::status)
				.containsExactly(MovementStatus.MOVED, MovementStatus.PENDING);

		Assertions.assertThat(movementWriter.reserved(execution())).as("another run's work is not ours")
				.isEmpty();
	}

	@Test
	void anOperationSettledByAnEarlierAttemptIsReadBackAsSettled() {
		long execution = execution();
		long file = catalogued();

		PreparedMovement prepared = movementWriter.prepare(execution, List.of(request(file))).getFirst();

		movementWriter.markMoved(execution, List.of(prepared.movementPublicId()));

		Assertions.assertThat(movementWriter.prepare(execution, List.of(request(file))).getFirst().status())
				.isEqualTo(MovementStatus.MOVED);
	}

	@Test
	void settlingAsMovedStampsWhenItMoved() {
		long execution = execution();
		long file = catalogued();

		PreparedMovement prepared = movementWriter.prepare(execution, List.of(request(file))).getFirst();

		Assertions.assertThat(movementWriter.markMoved(execution, List.of(prepared.movementPublicId()))).isEqualTo(1);
		Assertions.assertThat(statusOf(execution, file)).isEqualTo("MOVED");
		Assertions.assertThat(movedAtOf(execution, file)).isNotNull();
	}

	/** An operation that decided against an effect never moved anything. */
	@Test
	void settlingAsSkippedLeavesNoMoveAndKeepsTheReservedIdentity() {
		long execution = execution();
		long file = catalogued();

		PreparedMovement prepared = movementWriter.prepare(execution, List.of(request(file))).getFirst();

		movementWriter.markSkipped(execution, List.of(prepared.movementPublicId()), MovementReason.TARGET_EXISTS);

		Assertions.assertThat(statusOf(execution, file)).isEqualTo("SKIPPED");
		Assertions.assertThat(movedAtOf(execution, file)).isNull();
		Assertions.assertThat(reservedIdentityOf(execution, file)).isEqualTo(prepared.catalogFileEventPublicId());
	}

	@Test
	void settlingAsFailedLeavesNoMove() {
		long execution = execution();
		long file = catalogued();

		PreparedMovement prepared = movementWriter.prepare(execution, List.of(request(file))).getFirst();

		movementWriter.markFailed(execution, List.of(prepared.movementPublicId()), MovementReason.IO_ERROR);

		Assertions.assertThat(statusOf(execution, file)).isEqualTo("ERROR");
		Assertions.assertThat(movedAtOf(execution, file)).isNull();
	}

	/**
	 * Settling is once. A second call finding the operation already concluded must
	 * change nothing - otherwise a retry would move {@code moved_at} to whenever it
	 * happened to run.
	 */
	@Test
	void settlingAnAlreadySettledOperationChangesNothing() {
		long execution = execution();
		long file = catalogued();

		PreparedMovement prepared = movementWriter.prepare(execution, List.of(request(file))).getFirst();

		movementWriter.markMoved(execution, List.of(prepared.movementPublicId()));

		Assertions.assertThat(movementWriter.markSkipped(execution, List.of(prepared.movementPublicId()),
				MovementReason.TARGET_EXISTS)).isZero();
		Assertions.assertThat(statusOf(execution, file)).isEqualTo("MOVED");
	}

	/** A batch is one statement, whatever the size of the folder. */
	@Test
	void preparingAThousandOperationsIsOneBatch() {
		long execution = execution();

		List<MovementRequest> requests = new ArrayList<>();

		for (int index = 0; index < 1000; index++) {
			requests.add(request(catalogued()));
		}

		Assertions.assertThat(movementWriter.prepare(execution, requests)).hasSize(1000);
		Assertions.assertThat(rowsFor(execution)).isEqualTo(1000);
	}

	/** Two runs may both operate on one file; one run may not do it twice. */
	@Test
	void aDifferentExecutionMayPrepareTheSameFile() {
		long file = catalogued();
		long first = execution();
		long second = execution();

		PreparedMovement one = movementWriter.prepare(first, List.of(request(file))).getFirst();
		PreparedMovement two = movementWriter.prepare(second, List.of(request(file))).getFirst();

		Assertions.assertThat(two.movementPublicId()).isNotEqualTo(one.movementPublicId());
		Assertions.assertThat(two.catalogFileEventPublicId()).isNotEqualTo(one.catalogFileEventPublicId());
	}

	private MovementRequest request(long catalogFileId) {
		return new MovementRequest(catalogFileId, library.resolve("de-" + catalogFileId + ".jpg"),
				library.resolve("para-" + catalogFileId + ".jpg"), null);
	}

	private long execution() {
		return inserted("""
				INSERT INTO execution (execution_public_id, execution_type, status, created_at, available_at)
				VALUES (?, 'EXPLORER_RENAME', 'RUNNING', now(), now())
				RETURNING id
				""");
	}

	private long catalogued() {
		return inserted("""
				INSERT INTO catalog_file (catalog_file_public_id, extension, size_bytes, modified_at, file_type,
						lifecycle_status)
				VALUES (?, 'jpg', 1024, now(), 'PHOTO', 'ACTIVE')
				RETURNING id
				""");
	}

	private long inserted(String sql) {
		return jdbcTemplate.queryForObject(sql, Long.class, UUID.randomUUID());
	}

	private String statusOf(long executionId, long catalogFileId) {
		return single("SELECT status FROM movement WHERE execution_id = ? AND catalog_file_id = ?", executionId,
				catalogFileId, String.class);
	}

	private Object movedAtOf(long executionId, long catalogFileId) {
		return single("SELECT moved_at FROM movement WHERE execution_id = ? AND catalog_file_id = ?", executionId,
				catalogFileId, Object.class);
	}

	private Object preparedAtOf(long executionId, long catalogFileId) {
		return single("SELECT prepared_at FROM movement WHERE execution_id = ? AND catalog_file_id = ?", executionId,
				catalogFileId, Object.class);
	}

	private UUID reservedIdentityOf(long executionId, long catalogFileId) {
		return single("""
				SELECT catalog_file_event_public_id FROM movement
				WHERE execution_id = ? AND catalog_file_id = ?
				""", executionId, catalogFileId, UUID.class);
	}

	private long rowsFor(long executionId) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM movement WHERE execution_id = ?", Long.class,
				executionId);
	}

	/**
	 * Read in the transaction the writer wrote in.
	 *
	 * <p>
	 * A connection of its own would be a second transaction, and this class is
	 * {@code @Transactional}: what the writer prepared is uncommitted until the
	 * rollback, so a fresh connection finds no row at all and the reader fails on
	 * a result set that was never positioned.
	 *
	 * @return null where there is no row, which is what several of these cases are
	 * about
	 */
	private <T> T single(String sql, long executionId, long catalogFileId, Class<T> type) {
		List<T> found = jdbcTemplate.queryForList(sql, type, executionId, catalogFileId);

		return found.isEmpty() ? null : found.getFirst();
	}
}