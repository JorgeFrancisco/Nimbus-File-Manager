package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import br.com.jorgemelo.nimbusfilemanager.shared.application.LocationChangeException;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.AppliedLocationChange;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.FolderRelocation;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.CatalogFactProvenance;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.LocationChange;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationChangeFailure;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.FilesystemIdentity;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * The one way a catalogued file changes where it is.
 *
 * <p>
 * There is nothing here but the call and the translation of its refusals. The
 * ordering, the lock, the check that the destination is free and the writing of
 * both the fact and the placement are the database's, in one transaction, and
 * that is deliberate rather than a preference for stored procedures: the checks
 * and the write have to happen with nothing able to slip between them, and no
 * arrangement of Java statements over two tables can promise that. What used to
 * be here instead - read, decide, write, in each of the features that moved a
 * file - is exactly how two of them could agree a name was free and both take
 * it.
 *
 * <p>
 * Callers get a value object and an exception carrying a
 * {@link LocationChangeFailure}. They do not see function names, SQL, or
 * SQLSTATE, and they should not: those are how this is implemented today.
 *
 * <p>
 * No transaction is opened here. A single statement is atomic on its own, and a
 * caller that has more to write joins this to its own transaction by declaring
 * one - in which case anything it has pending through JPA must already be
 * flushed, since this reads the tables directly and will not see a change still
 * sitting in the persistence context.
 */
@Repository
public class CatalogLocationWriter {

	private static final String RENAME = """
			SELECT event_id, current_path, path_key, current_folder, replayed
			FROM rename_catalog_file(CAST(:catalogFileId AS bigint), CAST(:eventId AS uuid),
			     CAST(:expectedOldPath AS text), CAST(:newPath AS text), CAST(:pathFlavor AS text),
			     ROW(CAST(:occurredAt AS timestamptz), CAST(:source AS text), CAST(:evidenceKind AS text),
			         CAST(:identityKind AS text), CAST(:identityScope AS text),
			         CAST(:identityValue AS text))::catalog_fact_provenance)
			""";

	private static final String MOVE = """
			SELECT event_id, current_path, path_key, current_folder, replayed
			FROM move_catalog_file(CAST(:catalogFileId AS bigint), CAST(:eventId AS uuid),
			     CAST(:expectedOldPath AS text), CAST(:newPath AS text), CAST(:pathFlavor AS text),
			     ROW(CAST(:occurredAt AS timestamptz), CAST(:source AS text), CAST(:evidenceKind AS text),
			         CAST(:identityKind AS text), CAST(:identityScope AS text),
			         CAST(:identityValue AS text))::catalog_fact_provenance)
			""";

	private static final String RELOCATE = """
			SELECT event_id, current_path, path_key, current_folder, replayed
			FROM relocate_catalog_file(CAST(:catalogFileId AS bigint), CAST(:eventId AS uuid),
			     CAST(:expectedOldPath AS text), CAST(:newPath AS text), CAST(:pathFlavor AS text),
			     ROW(CAST(:occurredAt AS timestamptz), CAST(:source AS text), CAST(:evidenceKind AS text),
			         CAST(:identityKind AS text), CAST(:identityScope AS text),
			         CAST(:identityValue AS text))::catalog_fact_provenance)
			""";

	private static final String RELOCATE_FOLDER = """
			SELECT files_relocated, replayed
			FROM relocate_catalog_folder_contents(CAST(:catalogFileIds AS bigint[]),
			     CAST(:eventIds AS uuid[]), CAST(:oldRoot AS text), CAST(:newRoot AS text),
			     CAST(:pathFlavor AS text),
			     ROW(CAST(:occurredAt AS timestamptz), CAST(:source AS text), CAST(:evidenceKind AS text),
			         CAST(:identityKind AS text), CAST(:identityScope AS text),
			         CAST(:identityValue AS text))::catalog_fact_provenance)
			""";

	private static final RowMapper<AppliedLocationChange> APPLIED = CatalogLocationWriter::readApplied;

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public CatalogLocationWriter(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * The file keeps its folder and takes a new name.
	 *
	 * @throws LocationChangeException carrying
	 * {@link LocationChangeFailure#INVALID_CHANGE} when the new path is in a
	 * different folder - that is a move, and calling the wrong one would put the
	 * wrong kind of fact in the history
	 */
	public AppliedLocationChange rename(LocationChange change) {
		return apply(RENAME, change);
	}

	/** The file goes to another folder, with or without a new name on arrival. */
	public AppliedLocationChange move(LocationChange change) {
		return apply(MOVE, change);
	}

	/**
	 * The file goes somewhere, and which of the two it was is worked out rather
	 * than stated.
	 *
	 * <p>
	 * For a caller that computes a destination instead of naming an intention -
	 * organizing by date decides a folder and keeps the file's name, so whether
	 * that is a move or a rename is an outcome. A caller that does know should say
	 * so with {@link #rename} or {@link #move} and get the refusal that goes with
	 * being wrong.
	 */
	public AppliedLocationChange relocate(LocationChange change) {
		return apply(RELOCATE, change);
	}

	/**
	 * Every catalogued file under one folder goes to another, in one statement.
	 *
	 * <p>
	 * Two round trips whatever the size of the folder: one to learn which files
	 * are under it, one to move them. Calling the single-file door once per file
	 * would be fifty thousand round trips on a library that size, which is not a
	 * slower version of this - it is a different outage.
	 *
	 * <p>
	 * The identities are minted here, one per file, because that is where the list
	 * of files first exists. A retry that reaches this method again mints new ones
	 * and finds nothing under the old folder, so it writes nothing and reports
	 * zero - safe, but safe by precondition rather than by identity. The function
	 * itself accepts a replayed batch by its identities; using that needs an
	 * operation identity persisted before the work, which the capability that has
	 * one will bring.
	 *
	 * @return how many files moved with the folder
	 */
	public int relocateFolderContents(List<Long> catalogFileIds, List<UUID> catalogFileEventPublicIds,
			FolderRelocation relocation) {
		if (catalogFileIds.isEmpty()) {
			return 0;
		}

		if (catalogFileIds.size() != catalogFileEventPublicIds.size()) {
			throw new IllegalArgumentException("Every file relocated with a folder needs exactly one fact identity");
		}

		PathFlavor flavor = PathFlavor.of(relocation.newRoot());

		MapSqlParameterSource parameters = new MapSqlParameterSource()
				.addValue("catalogFileIds", catalogFileIds.toArray(Long[]::new))
				.addValue("eventIds", catalogFileEventPublicIds.toArray(UUID[]::new))
				.addValue("oldRoot", PathUtils.normalize(relocation.oldRoot()))
				.addValue("newRoot", PathUtils.normalize(relocation.newRoot()))
				.addValue("pathFlavor", flavor.name());

		addProvenance(parameters, relocation.provenance());

		try {
			return jdbcTemplate.queryForObject(RELOCATE_FOLDER, parameters,
					(rs, _) -> rs.getInt("files_relocated"));
		} catch (DataAccessException exception) {
			throw translate(exception);
		}
	}

	/**
	 * The six fields the composite takes, bound one by one rather than as a driver
	 * type: the SQL builds the row, so nothing here has to know how the driver
	 * spells a composite, and a field added to the type is a compile error at the
	 * call rather than a silently missing value.
	 */
	private static void addProvenance(MapSqlParameterSource parameters, CatalogFactProvenance provenance) {
		FilesystemIdentity identity = provenance.identity();

		parameters.addValue("occurredAt", OffsetDateTime.ofInstant(provenance.occurredAt(), ZoneOffset.UTC))
				.addValue("source", provenance.source()).addValue("evidenceKind", provenance.evidence())
				.addValue("identityKind", identity == null ? null : identity.kind().name())
				.addValue("identityScope", identity == null ? null : identity.scope())
				.addValue("identityValue", identity == null ? null : identity.value());
	}

	private AppliedLocationChange apply(String sql, LocationChange change) {
		// Read from the destination, which is the path being claimed and the one the
		// lock and the canonical key are computed from. Both ends of a change are on
		// the same file system in every case that reaches here - a file moving between
		// a Windows path and a POSIX one is not a move, it is a copy and a delete.
		PathFlavor flavor = PathFlavor.of(change.newPath());

		MapSqlParameterSource parameters = new MapSqlParameterSource()
				.addValue("catalogFileId", change.catalogFileId()).addValue("eventId", change.eventId())
				.addValue("expectedOldPath", PathUtils.normalize(change.expectedCurrentPath()))
				.addValue("newPath", PathUtils.normalize(change.newPath()))
				.addValue("pathFlavor", flavor.name());

		addProvenance(parameters, change.provenance());

		try {
			return jdbcTemplate.queryForObject(sql, parameters, APPLIED);
		} catch (DataAccessException exception) {
			throw translate(exception);
		}
	}

	/**
	 * Every refusal the door raises arrives as a SQLSTATE of its own, so this is a
	 * lookup rather than a reading of the message. Anything else is a database
	 * problem and is left as it came: dressing an unknown failure up as one of
	 * these would tell the caller a story about what went wrong.
	 */
	private RuntimeException translate(DataAccessException exception) {
		Throwable cause = NestedExceptionUtils.getMostSpecificCause(exception);

		if (!(cause instanceof SQLException sqlException)) {
			return exception;
		}

		LocationChangeFailure failure = failureOf(sqlException.getSQLState());

		return failure == null ? exception : new LocationChangeException(failure, cause.getMessage(), exception);
	}

	private static LocationChangeFailure failureOf(String sqlState) {
		return switch (sqlState) {
			case "NB001" -> LocationChangeFailure.CATALOG_FILE_NOT_FOUND;
			case "NB002" -> LocationChangeFailure.LOCATION_NOT_FOUND;
			case "NB003" -> LocationChangeFailure.STALE_LOCATION;
			case "NB004" -> LocationChangeFailure.PATH_OCCUPIED;
			case "NB005" -> LocationChangeFailure.IDEMPOTENCY_CONFLICT;
			case "NB006" -> LocationChangeFailure.INVALID_ARGUMENT;
			case "NB007" -> LocationChangeFailure.INVALID_CHANGE;
			case "NB008" -> LocationChangeFailure.MULTIPLE_PRESENT_FILES;
			case null, default -> null;
		};
	}

	private static AppliedLocationChange readApplied(ResultSet resultSet, int rowNumber) throws SQLException {
		return new AppliedLocationChange(resultSet.getLong("event_id"), resultSet.getString("current_path"),
				resultSet.getString("path_key"), resultSet.getString("current_folder"),
				resultSet.getBoolean("replayed"));
	}
}