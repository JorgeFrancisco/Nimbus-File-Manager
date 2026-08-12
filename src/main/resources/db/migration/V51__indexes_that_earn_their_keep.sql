-- Seven indexes that index nothing new, and one the deletes needed.
--
-- Every index here was checked against the one that makes it redundant: two are
-- exact copies of a UNIQUE constraint's own index, and five are leading-column
-- prefixes of a wider index that answers the same lookups. A prefix costs the
-- same writes as the wider index and answers no query the wider one cannot,
-- which on a catalogue that ingests in batches is paid on every insert.
--
--   ix_app_setting_key             = app_setting_setting_key_key (UNIQUE)
--   ix_app_user_username           = app_user_username_key (UNIQUE)
--   ix_execution_error_execution   ⊂ ix_execution_error_execution_created
--   ix_execution_error_type        ⊂ ix_execution_error_type_path_created
--   ix_execution_step_execution    ⊂ ix_execution_step_execution_created
--   ix_media_fingerprint_file      ⊂ uk_media_fingerprint
--   ix_fingerprint_failure_lookup  ⊂ idx_fingerprint_failure_reason
--
-- The two fingerprint tables are the ones worth naming: both carry a foreign key
-- to catalog_file, and in both the constraint's own UNIQUE leads with
-- catalog_file_id, so the delete path keeps its index after the drop.
--
-- What is added is the mirror image. similarity_relation references catalog_file
-- twice and only the second reference was indexed, so deleting a file - a purge
-- of missing rows, or forgetting a library - had to scan the whole relation
-- table to find the rows naming it first. That is the largest table the analysis
-- produces, and both purges delete in bulk.
--
-- Deliberately not added: similarity_grouping.execution_id, whose foreign key
-- sets null when an execution is deleted. That table holds one row per analysis
-- run, so the scan is over a handful of rows, and an index there would be paid
-- for by every publication to save nothing measurable.

DROP INDEX IF EXISTS ix_app_setting_key;
DROP INDEX IF EXISTS ix_app_user_username;
DROP INDEX IF EXISTS ix_execution_error_execution;
DROP INDEX IF EXISTS ix_execution_error_type;
DROP INDEX IF EXISTS ix_execution_step_execution;
DROP INDEX IF EXISTS ix_media_fingerprint_file;
DROP INDEX IF EXISTS ix_fingerprint_failure_lookup;

CREATE INDEX IF NOT EXISTS ix_similarity_relation_first ON similarity_relation (first_catalog_file_id);

COMMENT ON INDEX ix_similarity_relation_first IS
    'The half of the relation that had no index. Deleting a catalog file cascades through both ends, and without this one the first end was found by scanning the table.';