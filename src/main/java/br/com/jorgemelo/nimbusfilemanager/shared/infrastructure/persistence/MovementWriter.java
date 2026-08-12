package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.MovementRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PreparedMovement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

/**
 * Writing down what is about to be attempted, and later how it went.
 *
 * <p>
 * Preparing is idempotent, and that is the entire reason this class exists. An
 * execution that dies is re-dispatched with the same identity, and its second
 * attempt must find the operations the first one wrote rather than invent a
 * parallel set - otherwise the same work is recorded twice under two identities,
 * and the write door has no way to tell a repeat from a second job.
 *
 * <p>
 * Nothing here is per-file. Preparing ten thousand operations is two statements
 * and settling them is one, because a folder holding that many files is the case
 * this was built for and a round trip each would be a different outage.
 *
 * <p>
 * No transaction is opened. Settling an operation has to commit with the fact it
 * produced, so the caller declares one transaction around both - which is what
 * keeps a file from being moved and recorded while its operation still says
 * pending.
 */
@Repository
public class MovementWriter {

	private static final String PREPARE = """
			INSERT INTO movement (movement_public_id, execution_id, catalog_file_id, catalog_file_event_public_id,
					requested_source_path, requested_target_path, status, reason, prepared_at)
			SELECT i.movement_public_id, :executionId, i.catalog_file_id, i.catalog_file_event_public_id,
			       i.requested_source_path, i.requested_target_path, 'PENDING', i.reason, CURRENT_TIMESTAMP
			FROM unnest(CAST(:catalogFileIds AS bigint[]), CAST(:movementPublicIds AS uuid[]),
			     CAST(:catalogFileEventPublicIds AS uuid[]), CAST(:requestedSourcePaths AS text[]),
			     CAST(:requestedTargetPaths AS text[]), CAST(:reasons AS varchar[]))
			     AS i(catalog_file_id, movement_public_id, catalog_file_event_public_id, requested_source_path,
			          requested_target_path, reason)
			ON CONFLICT (execution_id, catalog_file_id) DO NOTHING
			""";

	private static final String READ_PREPARED = """
			SELECT m.id, m.movement_public_id, m.catalog_file_event_public_id, m.catalog_file_id,
			       m.requested_source_path, m.requested_target_path, m.status
			FROM movement m
			WHERE m.execution_id = :executionId
			  AND m.catalog_file_id = ANY(CAST(:catalogFileIds AS bigint[]))
			ORDER BY m.catalog_file_id
			""";

	/**
	 * Everything one run put on record, whatever the catalog says now.
	 *
	 * <p>
	 * The reading above needs the files to ask about, which a retry can only get
	 * by working them out from where things currently are - and after the first
	 * attempt's effect, that no longer describes what it set out to do. This asks
	 * the operations themselves, which is the record that was written down before
	 * anything moved for exactly this reason.
	 */
	private static final String READ_RESERVED = """
			SELECT m.id, m.movement_public_id, m.catalog_file_event_public_id, m.catalog_file_id,
			       m.requested_source_path, m.requested_target_path, m.status
			FROM movement m
			WHERE m.execution_id = :executionId
			ORDER BY m.catalog_file_id
			""";

	/**
	 * The clock is the database's, matching every other instant this schema stamps
	 * itself, and the guard is the state rather than the row: settling an operation
	 * twice is a bug the second time round, and letting it through would move
	 * {@code moved_at} to whenever the retry ran.
	 */
	private static final String SETTLE = """
			UPDATE movement
			   SET status = CAST(:status AS varchar),
			       reason = COALESCE(CAST(:reason AS varchar), reason),
			       moved_at = CASE WHEN :status = 'MOVED' THEN CURRENT_TIMESTAMP ELSE NULL END
			 WHERE execution_id = :executionId
			   AND movement_public_id = ANY(CAST(:movementPublicIds AS uuid[]))
			   AND status = 'PENDING'
			""";

	/**
	 * Scoped by nothing but the identities, because the operation being reversed
	 * belongs to the execution that did the moving and not to the one undoing it.
	 * Only a movement that actually moved can be undone, which is what the status
	 * guard says.
	 */
	private static final String UNDO = """
			UPDATE movement
			   SET status = 'UNDONE'
			 WHERE movement_public_id = ANY(CAST(:movementPublicIds AS uuid[]))
			   AND status = 'MOVED'
			""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public MovementWriter(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Puts the operations on record and hands back what is there, whether this
	 * attempt wrote it or an earlier one did.
	 *
	 * <p>
	 * Two attempts racing each other both insert; the unique constraint on the
	 * execution and the file decides, and both then read the same rows. Neither
	 * learns which of them won, and neither needs to - what matters is that they
	 * agree on the identities.
	 */
	public List<PreparedMovement> prepare(long executionId, List<MovementRequest> requests) {
		if (requests.isEmpty()) {
			return List.of();
		}

		Long[] catalogFileIds = requests.stream().map(MovementRequest::catalogFileId).toArray(Long[]::new);

		MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("executionId", executionId)
				.addValue("catalogFileIds", catalogFileIds)
				.addValue("movementPublicIds", identities(requests.size()))
				.addValue("catalogFileEventPublicIds", identities(requests.size()))
				.addValue("requestedSourcePaths", paths(requests, true))
				.addValue("requestedTargetPaths", paths(requests, false)).addValue("reasons", reasons(requests));

		jdbcTemplate.update(PREPARE, parameters);

		return jdbcTemplate.query(READ_PREPARED,
				new MapSqlParameterSource().addValue("executionId", executionId).addValue("catalogFileIds",
						catalogFileIds),
				MovementWriter::readPrepared);
	}

	/** @return the operations this run reserved, in the order they were prepared */
	public List<PreparedMovement> reserved(long executionId) {
		return jdbcTemplate.query(READ_RESERVED, new MapSqlParameterSource().addValue("executionId",
				executionId), MovementWriter::readPrepared);
	}

	/**
	 * The operations produced their facts. Called inside the same transaction as
	 * the write door, so a file never ends up moved and recorded while its
	 * operation still claims to be pending.
	 *
	 * @return how many were still pending and therefore settled by this call
	 */
	public int markMoved(long executionId, Collection<UUID> movementPublicIds) {
		return settle(executionId, movementPublicIds, MovementStatus.MOVED, null);
	}

	/** The operations decided against an effect, and {@code reason} says why. */
	public int markSkipped(long executionId, Collection<UUID> movementPublicIds, MovementReason reason) {
		return settle(executionId, movementPublicIds, MovementStatus.SKIPPED, reason);
	}

	/**
	 * The operations failed without an effect. The detail belongs to
	 * {@code execution_error}; what is recorded here is that they are over.
	 */
	public int markFailed(long executionId, Collection<UUID> movementPublicIds, MovementReason reason) {
		return settle(executionId, movementPublicIds, MovementStatus.ERROR, reason);
	}

	/**
	 * The effect of these operations has been reversed by others. Their own record
	 * is untouched beyond the state: they did move the file, they still say when,
	 * and the reversal is a fact belonging to the movements that performed it.
	 */
	public int markUndone(Collection<UUID> movementPublicIds) {
		if (movementPublicIds.isEmpty()) {
			return 0;
		}

		return jdbcTemplate.update(UNDO, new MapSqlParameterSource().addValue("movementPublicIds",
				movementPublicIds.toArray(UUID[]::new)));
	}

	private int settle(long executionId, Collection<UUID> movementPublicIds, MovementStatus status,
			MovementReason reason) {
		if (movementPublicIds.isEmpty()) {
			return 0;
		}

		return jdbcTemplate.update(SETTLE, new MapSqlParameterSource().addValue("executionId", executionId)
				.addValue("movementPublicIds", movementPublicIds.toArray(UUID[]::new))
				.addValue("status", status.name()).addValue("reason", reason == null ? null : reason.name()));
	}

	/**
	 * One identity per operation, minted here because this is where the list of
	 * operations first exists. A conflicting insert throws them away and the read
	 * returns the ones already on record, which is exactly what a retry needs.
	 */
	private UUID[] identities(int count) {
		UUID[] identities = new UUID[count];

		for (int index = 0; index < count; index++) {
			identities[index] = UuidV7.generate();
		}

		return identities;
	}

	private String[] paths(List<MovementRequest> requests, boolean source) {
		return requests.stream()
				.map(request -> PathUtils.normalize(source ? request.requestedSource() : request.requestedTarget()))
				.toArray(String[]::new);
	}

	private String[] reasons(List<MovementRequest> requests) {
		return requests.stream().map(request -> request.reason() == null ? null : request.reason().name())
				.toArray(String[]::new);
	}

	private static PreparedMovement readPrepared(ResultSet resultSet, int rowNumber) throws SQLException {
		return new PreparedMovement(resultSet.getLong("id"),
				resultSet.getObject("movement_public_id", UUID.class),
				resultSet.getObject("catalog_file_event_public_id", UUID.class),
				resultSet.getObject("catalog_file_id", Long.class), resultSet.getString("requested_source_path"),
				resultSet.getString("requested_target_path"),
				MovementStatus.valueOf(resultSet.getString("status")));
	}
}