package br.com.jorgemelo.nimbusfilemanager.backup.infrastructure.persistence;

import java.io.IOException;
import java.sql.SQLException;

import org.postgresql.copy.CopyManager;

/**
 * One {@code COPY} call, so both directions share the same connection handling
 * and error translation instead of repeating it. Its own file because nested
 * types are not used in this project.
 */
@FunctionalInterface
interface CopyOperation {

	long run(CopyManager manager) throws SQLException, IOException;
}