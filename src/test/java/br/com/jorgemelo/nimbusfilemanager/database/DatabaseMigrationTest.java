package br.com.jorgemelo.nimbusfilemanager.database;

import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class DatabaseMigrationTest {

	private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V1__initial_schema.sql");

	@Test
	void mediaFingerprintStoresWideHashAndSsimSampleAsBytea() throws Exception {
		String migration = Files.readString(MIGRATION);

		Assertions.assertThat(migration).contains("hash_bytes BYTEA", "sample_bytes BYTEA",
				"FFMPEG_LANCZOS_PHASH_256_V1", "ck_media_fingerprint_hash_storage",
				"ck_media_fingerprint_phash_payload");
	}

	@Test
	void shouldCreateAllCoreTables() throws Exception {
		String migration = Files.readString(MIGRATION);

		Assertions.assertThat(migration).contains("CREATE TABLE catalog_file", "CREATE TABLE catalog_file_location",
				"CREATE TABLE media_metadata", "CREATE TABLE photo", "CREATE TABLE video", "CREATE TABLE execution",
				"CREATE TABLE movement", "CREATE TABLE analysis_error", "CREATE TABLE execution_step",
				"CREATE TABLE app_user", "CREATE TABLE app_setting", "CREATE TABLE user_access_log",
				"CREATE TABLE user_page_preference");
	}

	@Test
	void shouldUseBigserialIdentityColumnsForPostgres() throws Exception {
		String migration = Files.readString(MIGRATION);

		Assertions.assertThat(migration).contains("BIGSERIAL PRIMARY KEY")
				.doesNotContain("INTEGER PRIMARY KEY AUTOINCREMENT");
	}

	/**
	 * The table was created as {@code analysis_error}, named for the only thing
	 * that wrote to it then. Conversions and duplicate deletions record their
	 * per-file failures there too, so the rename is what keeps the name describing
	 * the content instead of one of its writers - along with every constraint and
	 * index that carried the old word.
	 */
	@Test
	void executionErrorCarriesItsNewNameThroughConstraintsAndIndexes() throws Exception {
		String rename = Files.readString(
				Path.of("src/main/resources/db/migration/V5__rename_analysis_error_to_execution_error.sql"));

		Assertions.assertThat(rename).contains("ALTER TABLE analysis_error RENAME TO execution_error",
				"RENAME CONSTRAINT uk_analysis_error_public_id TO uk_execution_error_public_id",
				"RENAME CONSTRAINT fk_analysis_error_execution TO fk_execution_error_execution",
				"RENAME CONSTRAINT analysis_error_pkey TO execution_error_pkey",
				"ALTER INDEX ix_analysis_error_execution RENAME TO ix_execution_error_execution",
				"ALTER INDEX ix_analysis_error_lower_path RENAME TO ix_execution_error_lower_path",
				"ALTER INDEX ix_analysis_error_type_path_created RENAME TO ix_execution_error_type_path_created");
	}

	/**
	 * The reason a move failed moves to the one place every screen reads, and the
	 * column goes with it. A blank message is not carried over: it would repeat
	 * what the movement's own ERROR status already says and fill an execution's
	 * error list with rows that answer nothing.
	 */
	@Test
	void movementErrorsAreCarriedIntoExecutionErrorBeforeTheColumnGoes() throws Exception {
		String migration = Files
				.readString(Path.of("src/main/resources/db/migration/V7__movement_errors_move_to_execution_error.sql"));

		Assertions.assertThat(migration).contains("INSERT INTO execution_error", "FROM movement m",
				"m.error_message IS NOT NULL", "btrim(m.error_message) <> ''",
				"ALTER TABLE movement DROP COLUMN error_message");
	}

	@Test
	void shouldCreateQueryPerformanceIndexes() throws Exception {
		String migration = Files.readString(MIGRATION);

		Assertions.assertThat(migration).contains("ix_catalog_file_location_current_path",
				"ix_catalog_file_location_lower_current_path", "ix_catalog_file_location_lower_current_folder",
				"ix_catalog_file_lifecycle_sha256_size", "ix_catalog_file_lifecycle_id",
				"ix_catalog_file_lower_extension", "ix_media_metadata_rebuild_filters",
				"ix_video_upper_trim_video_codec", "ix_analysis_error_execution_created",
				"ix_analysis_error_type_path_created", "ix_analysis_error_lower_path", "ix_execution_finished_status",
				"ix_execution_step_execution_created");
	}

	@Test
	void shouldStoreCoordinatesAndFloatsAsDoublePrecision() throws Exception {
		String migration = Files.readString(MIGRATION);

		Assertions.assertThat(migration)
				.contains("latitude DOUBLE PRECISION", "longitude DOUBLE PRECISION", "fps DOUBLE PRECISION",
						"duration_seconds DOUBLE PRECISION")
				.doesNotContain("latitude REAL", "longitude REAL", "fps REAL", "duration_seconds REAL");
	}

	@Test
	void shouldModelCatalogFileLocationAsOneToOneSharingCatalogFileId() throws Exception {
		String migration = Files.readString(MIGRATION);

		Assertions
				.assertThat(migration).contains("CREATE TABLE catalog_file_location (",
						"catalog_file_id BIGINT PRIMARY KEY", "fk_catalog_file_location_file")
				.doesNotContain("CREATE TABLE media_location (");
	}

	@Test
	void shouldTypeUserRoleWithACheckConstraint() throws Exception {
		String migration = Files.readString(MIGRATION);

		// role is an enum, persisted as VARCHAR(30) and guarded by a CHECK.
		Assertions.assertThat(migration).contains("role VARCHAR(30) NOT NULL", "ck_app_user_role",
				"CHECK (role IN ('ADMIN', 'USER'))");
	}

	@Test
	void shouldUseASingleUniqueConstraintPrefix() throws Exception {
		String migration = Files.readString(MIGRATION);

		// unique constraints all use uk_ (the majority prefix); the
		// former uq_ on the fingerprint tables was aligned.
		Assertions.assertThat(migration).doesNotContain("CONSTRAINT uq_", " uq_").contains("uk_media_fingerprint",
				"uk_fingerprint_failure");
	}

	@Test
	void shouldAddOptimisticLockVersionColumnsToConcurrentlyUpdatedTables() throws Exception {
		String migration = Files.readString(MIGRATION);

		// Exactly two @Version columns: catalog_file and app_user (the concurrently
		// updated tables). No other table gets one speculatively.
		Assertions.assertThat(countOccurrences(migration, "version BIGINT NOT NULL DEFAULT 0")).isEqualTo(2);
	}

	private static int countOccurrences(String haystack, String needle) {
		int count = 0;
		int from = 0;

		while (true) {
			int at = haystack.indexOf(needle, from);
			if (at < 0) {
				return count;
			}
			count++;
			from = at + needle.length();
		}
	}

	@Test
	void shouldModelLifecycleStatusWithCheckConstraintAndNoBooleans() throws Exception {
		String migration = Files.readString(MIGRATION);

		Assertions.assertThat(migration)
				.contains("lifecycle_status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE'", "ck_catalog_file_lifecycle_status",
						"CHECK (lifecycle_status IN ('ACTIVE', 'MISSING', 'DELETED'))")
				.doesNotContain("exists_flag BOOLEAN", "deleted BOOLEAN");
	}

	@Test
	void shouldNotCreateLowSelectivityBooleanIndexes() throws Exception {
		String migration = Files.readString(MIGRATION);

		Assertions.assertThat(migration).doesNotContain("CREATE INDEX ix_catalog_file_exists ON",
				"CREATE INDEX ix_catalog_file_deleted ON", "CREATE INDEX ix_catalog_file_extension ON");
	}

	@Test
	void shouldCreateAppUserTableWithConfirmationSupport() throws Exception {
		String migration = Files.readString(MIGRATION);

		Assertions.assertThat(migration).contains("username VARCHAR(100) NOT NULL UNIQUE",
				"password_hash VARCHAR(255) NOT NULL", "two_factor_enabled BOOLEAN NOT NULL DEFAULT FALSE",
				"two_factor_secret VARCHAR(64)", "confirmation_token VARCHAR(64)",
				"confirmation_token_expires_at TIMESTAMP", "ix_app_user_username", "ix_app_user_enabled",
				"ix_app_user_confirmation_token");
	}

	@Test
	void shouldAddRequiredPasswordChangeFlag() throws Exception {
		Assertions.assertThat(Files.readString(MIGRATION))
				.contains("password_change_required BOOLEAN NOT NULL DEFAULT FALSE");
	}

	@Test
	void shouldCreateSettingsAndUserAccessLogTables() throws Exception {
		String migration = Files.readString(MIGRATION);

		Assertions.assertThat(migration).contains("setting_key VARCHAR(150) NOT NULL UNIQUE",
				"created_by_username VARCHAR(100) NOT NULL", "updated_by_username VARCHAR(100)",
				"event_type VARCHAR(50) NOT NULL", "ix_user_access_log_username_created",
				"ix_user_access_log_event_created");
	}

	@Test
	void shouldCreateUserPagePreferenceTable() throws Exception {
		String migration = Files.readString(MIGRATION);

		Assertions.assertThat(migration).contains("user_id BIGINT NOT NULL", "page_key VARCHAR(80) NOT NULL",
				"preference_key VARCHAR(80) NOT NULL",
				"CONSTRAINT uk_user_page_preference UNIQUE (user_id, page_key, preference_key)",
				"fk_user_page_preference_user", "ix_user_page_preference_user_page");
	}

	@Test
	void shouldCreateSpringBatchJobRepositoryTables() throws Exception {
		String migration = Files.readString(MIGRATION);

		Assertions.assertThat(migration)
				.contains("CREATE TABLE BATCH_JOB_INSTANCE", "CREATE TABLE BATCH_JOB_EXECUTION",
						"CREATE TABLE BATCH_JOB_EXECUTION_PARAMS", "CREATE TABLE BATCH_STEP_EXECUTION",
						"CREATE TABLE BATCH_STEP_EXECUTION_CONTEXT", "CREATE TABLE BATCH_JOB_EXECUTION_CONTEXT",
						"CREATE SEQUENCE BATCH_STEP_EXECUTION_SEQ", "CREATE SEQUENCE BATCH_JOB_EXECUTION_SEQ",
						"CREATE SEQUENCE BATCH_JOB_SEQ")
				.doesNotContain("BATCH_JOB_INSTANCE_SEQ");
	}

	@Test
	void shouldNotContainDestructiveOrSqliteSpecificStatements() throws Exception {
		String migration = Files.readString(MIGRATION);

		Assertions.assertThat(migration.toUpperCase()).doesNotContain("DROP TABLE", "ALTER TABLE", "DELETE FROM",
				"AUTOINCREMENT", "PRAGMA");
		Assertions.assertThat(migration).doesNotContain("schema_version");
	}

	@Test
	void shouldCreateMinimalTimelineIndexesMatchingKeysetQueries() throws Exception {
		String migration = Files.readString(MIGRATION);

		Assertions.assertThat(migration)
				.contains("ix_timeline_capture_date_file", "media_metadata(capture_date DESC, catalog_file_id DESC)",
						"WHERE capture_date IS NOT NULL", "ix_timeline_active_visual_file",
						"catalog_file(file_type, id)", "lifecycle_status = 'ACTIVE'", "file_type IN ('PHOTO', 'VIDEO')")
				.doesNotContain("CREATE INDEX CONCURRENTLY", "DROP INDEX", "DROP TABLE");
	}
}