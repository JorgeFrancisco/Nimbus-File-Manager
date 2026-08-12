package br.com.jorgemelo.nimbusfilemanager.shared.domain.model;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;

/**
 * The structure the catalog now rests on, proved against a real PostgreSQL.
 *
 * <p>
 * Written against JDBC rather than the entities on purpose. This slice removes
 * {@code file_key} and {@code file_name} from {@code catalog_file}, which the
 * mapped classes still declare; the schema has to be provable on its own while
 * the Java side is migrated in the slice that follows.
 *
 * <p>
 * What it guards is what a reader cannot see by looking at the DDL: that the
 * canonical key is the database's to compute, that two files may legitimately
 * name the same path, and that removing a file takes its location and its
 * history with it while merely changing its lifecycle takes nothing.
 */
class CatalogSchemaIntegrationTest extends SharedPostgresIntegrationTest {

	private static final String WINDOWS = "WINDOWS";
	private static final String POSIX = "POSIX";

	@Autowired
	private DataSource dataSource;

	@Test
	void bothWindowsSeparatorsNameTheSamePlace() throws SQLException {
		Assertions.assertThat(canonical("D:" + backslash() + "Fotos" + backslash() + "a.jpg", WINDOWS))
				.isEqualTo(canonical("D:/Fotos/a.jpg", WINDOWS));
	}

	@Test
	void caseNeverDistinguishesTwoWindowsPaths() throws SQLException {
		Assertions.assertThat(canonical("D:/Fotos/IMG_1.JPG", WINDOWS))
				.isEqualTo(canonical("d:/fotos/img_1.jpg", WINDOWS));
	}

	/**
	 * Renaming a file to another casing is a rename on Windows, and the canonical
	 * key deliberately does not move: the file did not go anywhere.
	 */
	@Test
	void aWindowsRenameThatOnlyChangesCaseKeepsTheSameKey() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			long file = catalogFile(connection);

			location(connection, file, "D:/Fotos/foto.jpg", WINDOWS);

			String before = pathKeyOf(connection, file);

			update(connection, "UPDATE catalog_file_location SET current_path = ? WHERE catalog_file_id = ?",
					"D:/Fotos/FOTO.JPG", file);

			Assertions.assertThat(pathKeyOf(connection, file)).isEqualTo(before);
		}
	}

	@Test
	void caseDistinguishesTwoPosixPaths() throws SQLException {
		Assertions.assertThat(canonical("/fotos/IMG_1.JPG", POSIX))
				.isNotEqualTo(canonical("/fotos/img_1.jpg", POSIX));
	}

	@Test
	void aPosixPathKeepsItsOwnSeparatorAndSpelling() throws SQLException {
		Assertions.assertThat(canonical("/fotos/Viagem/a.jpg", POSIX)).isEqualTo("/fotos/Viagem/a.jpg");
	}

	/** A trailing separator names the same folder; the root names itself. */
	@Test
	void aTrailingSeparatorIsDroppedButNeverTheWholePath() throws SQLException {
		Assertions.assertThat(canonical("/fotos/viagem/", POSIX)).isEqualTo("/fotos/viagem");
		Assertions.assertThat(canonical("/", POSIX)).isEqualTo("/");
	}

	/** A drive root and a filesystem root both name something; neither empties. */
	@Test
	void rootsSurviveCanonicalization() throws SQLException {
		Assertions.assertThat(canonical("D:" + backslash(), WINDOWS)).isEqualTo("d:");
		Assertions.assertThat(canonical("C:/", WINDOWS)).isEqualTo(canonical("C://", WINDOWS));
		Assertions.assertThat(canonical("/", POSIX)).isEqualTo("/");
	}

	/**
	 * A UNC path begins with two separators that are not a spelling mistake, and
	 * collapsing them would name a different machine's share.
	 */
	@Test
	void aUncPathKeepsTheSeparatorsThatMakeItOne() throws SQLException {
		Assertions.assertThat(canonical("//server/share/fotos/", WINDOWS)).isEqualTo("//server/share/fotos");
		Assertions.assertThat(canonical("//server/share", WINDOWS)).isEqualTo("//server/share");
	}

	/**
	 * The precondition this function is written against, stated as a test so it
	 * cannot be forgotten: it canonicalizes how a path is spelled, not how it is
	 * structured. Every path reaching the database has already been through
	 * {@code Path.normalize()}; resolving {@code .} and {@code ..} a second time
	 * here would be the same rule written in two languages.
	 */
	@Test
	void structureIsTheCallersToResolveAndThisFunctionLeavesItAlone() throws SQLException {
		Assertions.assertThat(canonical("D:/Fotos/./a.jpg", WINDOWS))
				.isNotEqualTo(canonical("D:/Fotos/a.jpg", WINDOWS));
		Assertions.assertThat(canonical("/home/x/../a", POSIX)).isEqualTo("/home/x/../a");
	}

	/**
	 * The names a photo library is actually full of. This is what makes lower()
	 * worth its collation dependency - and the reason the folding is described as
	 * Nimbus's own stable form rather than as Windows' own table, which it
	 * approximates without reproducing.
	 */
	@Test
	void accentedNamesFoldTogetherOnWindows() throws SQLException {
		Assertions.assertThat(canonical("D:/F\u00e9rias/a.jpg", WINDOWS))
				.isEqualTo(canonical("D:/F\u00c9RIAS/A.JPG", WINDOWS));
	}

	/** The folder is read off the path, so the two can never disagree. */
	@Test
	void theFolderIsDerivedFromThePathAndNeverSupplied() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			long file = catalogFile(connection);

			location(connection, file,
					"D:" + backslash() + "Fotos" + backslash() + "Viagem" + backslash() + "a.jpg", WINDOWS);

			Assertions.assertThat(folderOf(connection, file))
					.isEqualTo("D:" + backslash() + "Fotos" + backslash() + "Viagem");

			long other = catalogFile(connection);

			Assertions.assertThatThrownBy(() -> update(connection, """
					INSERT INTO catalog_file_location (catalog_file_id, current_path, path_flavor, current_folder)
					VALUES (?, 'D:/x/a.jpg', 'WINDOWS', 'D:/wrong')
					""", other)).isInstanceOf(SQLException.class);
		}
	}

	/** On POSIX a backslash belongs to the name, so it must not cut the path. */
	@Test
	void aPosixNameMayContainABackslashWithoutBecomingAFolder() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			long file = catalogFile(connection);

			location(connection, file, "/home/a" + backslash() + "b.jpg", POSIX);

			Assertions.assertThat(folderOf(connection, file)).isEqualTo("/home");
		}
	}

	/** A volume qualifies an identity; on its own it qualifies nothing. */
	@Test
	void aScopeNeverArrivesWithoutTheIdentityItQualifies() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			long file = catalogFile(connection);

			Assertions.assertThatThrownBy(() -> update(connection, """
					INSERT INTO catalog_file_event (catalog_file_event_public_id, catalog_file_id, event_type, occurred_at, source,
					        filesystem_identity_scope)
					VALUES (gen_random_uuid(), ?, 'DISCOVERED', now(), 'INVENTORY', 'VOLUME-1')
					""", file)).isInstanceOf(SQLException.class);
		}
	}

	/**
	 * The flavor is validated where it is used rather than by a constraint listing
	 * the accepted values, so the set can grow without a migration - and an unknown
	 * one still cannot reach a row.
	 */
	@Test
	void anUnknownFlavorIsRefusedInsteadOfProducingAKey() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			long file = catalogFile(connection);

			Assertions.assertThatThrownBy(() -> location(connection, file, "D:/Fotos/a.jpg", "OS_2000"))
					.isInstanceOf(SQLException.class);
		}
	}

	@Test
	void theKeyIsTheDatabasesToComputeAndNoCallersToSupply() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			long file = catalogFile(connection);

			Assertions
					.assertThatThrownBy(() -> update(connection, """
							INSERT INTO catalog_file_location (catalog_file_id, current_path, path_flavor, path_key)
							VALUES (?, 'D:/Fotos/a.jpg', 'WINDOWS', 'whatever')
							""", file))
					.isInstanceOf(SQLException.class);
		}
	}

	@Test
	void movingTheFileRecomputesTheKey() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			long file = catalogFile(connection);

			location(connection, file, "D:/Fotos/a.jpg", WINDOWS);

			update(connection, "UPDATE catalog_file_location SET current_path = ? WHERE catalog_file_id = ?",
					"D:/Arquivo/a.jpg", file);

			Assertions.assertThat(pathKeyOf(connection, file)).isEqualTo("d:/arquivo/a.jpg");
		}
	}

	/** The rules the row is read under are data, so changing them re-reads it. */
	@Test
	void changingTheFlavorRecomputesTheKey() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			long file = catalogFile(connection);

			location(connection, file, "/Fotos/A.jpg", WINDOWS);

			Assertions.assertThat(pathKeyOf(connection, file)).isEqualTo("/fotos/a.jpg");

			update(connection, "UPDATE catalog_file_location SET path_flavor = ? WHERE catalog_file_id = ?", POSIX,
					file);

			Assertions.assertThat(pathKeyOf(connection, file)).isEqualTo("/Fotos/A.jpg");
		}
	}

	/**
	 * The invariant this schema deliberately does not enforce. A file that went
	 * MISSING keeps the last place it was seen, and another file may occupy that
	 * path in the meantime - making the two rows legal. Only one of them may be
	 * ACTIVE, and that rule spans both tables, so it belongs to the transactional
	 * functions rather than to a unique index.
	 */
	@Test
	void twoFilesMayNameTheSamePathWhenOnlyOneOfThemIsThere() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			long gone = catalogFile(connection);
			long arrived = catalogFile(connection);

			location(connection, gone, "D:/Fotos/a.jpg", WINDOWS);

			update(connection, "UPDATE catalog_file SET lifecycle_status = 'MISSING' WHERE id = ?", gone);

			location(connection, arrived, "D:/Fotos/a.jpg", WINDOWS);

			Assertions.assertThat(count(connection, "SELECT count(*) FROM catalog_file_location WHERE path_key = ?",
					"d:/fotos/a.jpg")).isEqualTo(2);
		}
	}

	@Test
	void oneFileNeverHasTwoLocations() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			long file = catalogFile(connection);

			location(connection, file, "D:/Fotos/a.jpg", WINDOWS);

			Assertions.assertThatThrownBy(() -> location(connection, file, "D:/Fotos/b.jpg", WINDOWS))
					.isInstanceOf(SQLException.class);
		}
	}

	@Test
	void removingTheFileTakesItsLocationAndItsHistoryWithIt() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			long file = catalogFile(connection);

			location(connection, file, "D:/Fotos/a.jpg", WINDOWS);
			event(connection, file, "DISCOVERED");

			update(connection, "DELETE FROM catalog_file WHERE id = ?", file);

			Assertions.assertThat(count(connection,
					"SELECT count(*) FROM catalog_file_location WHERE catalog_file_id = ?", file)).isZero();
			Assertions.assertThat(
					count(connection, "SELECT count(*) FROM catalog_file_event WHERE catalog_file_id = ?", file))
					.isZero();
		}
	}

	/** Soft delete is not removal: nothing cascades until the identity is gone. */
	@Test
	void changingTheLifecycleKeepsBothTheLocationAndTheHistory() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			long file = catalogFile(connection);

			location(connection, file, "D:/Fotos/a.jpg", WINDOWS);
			event(connection, file, "DISCOVERED");

			update(connection, "UPDATE catalog_file SET lifecycle_status = 'DELETED' WHERE id = ?", file);

			Assertions.assertThat(count(connection,
					"SELECT count(*) FROM catalog_file_location WHERE catalog_file_id = ?", file)).isEqualTo(1);
			Assertions.assertThat(
					count(connection, "SELECT count(*) FROM catalog_file_event WHERE catalog_file_id = ?", file))
					.isEqualTo(1);
		}
	}

	/** Half an identity is no evidence at all. */
	@Test
	void filesystemEvidenceTravelsWholeOrNotAtAll() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			long file = catalogFile(connection);

			Assertions.assertThatThrownBy(() -> update(connection, """
					INSERT INTO catalog_file_event (catalog_file_event_public_id, catalog_file_id, event_type, occurred_at, source,
					        filesystem_identity_kind)
					VALUES (gen_random_uuid(), ?, 'DISCOVERED', now(), 'INVENTORY', 'FRN')
					""", file)).isInstanceOf(SQLException.class);
		}
	}

	private String canonical(String path, String flavor) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection
						.prepareStatement("SELECT canonicalize_catalog_path(?, ?)")) {
			statement.setString(1, path);
			statement.setString(2, flavor);

			try (ResultSet rows = statement.executeQuery()) {
				rows.next();

				return rows.getString(1);
			}
		}
	}

	private long catalogFile(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO catalog_file (catalog_file_public_id, extension, size_bytes, modified_at, file_type, lifecycle_status)
				VALUES (?, 'jpg', 1024, now(), 'PHOTO', 'ACTIVE')
				RETURNING id
				""")) {
			statement.setObject(1, UUID.randomUUID());

			try (ResultSet rows = statement.executeQuery()) {
				rows.next();

				return rows.getLong(1);
			}
		}
	}

	private void location(Connection connection, long catalogFileId, String path, String flavor) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO catalog_file_location (catalog_file_id, current_path, path_flavor)
				VALUES (?, ?, ?)
				""")) {
			statement.setLong(1, catalogFileId);
			statement.setString(2, path);
			statement.setString(3, flavor);

			statement.executeUpdate();
		}
	}

	private void event(Connection connection, long catalogFileId, String eventType) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO catalog_file_event (catalog_file_event_public_id, catalog_file_id, event_type,
						occurred_at, source, evidence_kind)
				VALUES (?, ?, ?, now(), 'INVENTORY', 'PATH_FOUND')
				""")) {
			statement.setObject(1, UUID.randomUUID());
			statement.setLong(2, catalogFileId);
			statement.setString(3, eventType);

			statement.executeUpdate();
		}
	}

	private String pathKeyOf(Connection connection, long catalogFileId) throws SQLException {
		return single(connection, "SELECT path_key FROM catalog_file_location WHERE catalog_file_id = ?",
				catalogFileId);
	}

	private String folderOf(Connection connection, long catalogFileId) throws SQLException {
		return single(connection, "SELECT current_folder FROM catalog_file_location WHERE catalog_file_id = ?",
				catalogFileId);
	}

	private String single(Connection connection, String sql, long catalogFileId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, catalogFileId);

			try (ResultSet rows = statement.executeQuery()) {
				rows.next();

				return rows.getString(1);
			}
		}
	}

	private long count(Connection connection, String sql, Object argument) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setObject(1, argument);

			try (ResultSet rows = statement.executeQuery()) {
				rows.next();

				return rows.getLong(1);
			}
		}
	}

	private void update(Connection connection, String sql, Object... arguments) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int index = 0; index < arguments.length; index++) {
				statement.setObject(index + 1, arguments[index]);
			}

			statement.executeUpdate();
		}
	}

	/**
	 * Written as a character rather than as an escape so the separator cannot be
	 * miscounted by whoever reads it next.
	 */
	private String backslash() {
		return String.valueOf((char) 92);
	}
}