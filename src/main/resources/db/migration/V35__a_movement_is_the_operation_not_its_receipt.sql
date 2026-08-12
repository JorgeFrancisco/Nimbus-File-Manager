-- A movement stops being the receipt of something that already happened and
-- becomes the operation itself.
--
-- Every row here used to be written after the file had already moved, which made
-- it a perfectly good audit trail and a useless one for the question that
-- actually matters when a worker dies mid-operation: what was I in the middle of,
-- and under which identity? A retry could only mint fresh identities, so the
-- catalog could not tell a repeat of one job from a second job.
--
-- Now the row exists first. It is written before the file system is touched,
-- carrying the identity the resulting fact will have, and a retry finds it and
-- reuses it. That is what makes replay true rather than merely safe.
--
-- It is still only for operations the Nimbus issues. A file the watcher noticed
-- moving is not an operation and gets no movement - nobody ordered it.

-- ============================================================
-- The identity of the fact this operation may produce
-- ============================================================

ALTER TABLE movement ADD COLUMN catalog_file_event_public_id UUID;

-- Historical rows moved files long before this catalog recorded facts, so the
-- identity they get here was never reserved for anything and never will be
-- consumed. It is filled only because the column is NOT NULL from now on, and it
-- is the one place in this schema where the database mints a UUID: every
-- identity that will ever front a real fact comes from the application as a
-- UUIDv7.
UPDATE movement SET catalog_file_event_public_id = gen_random_uuid()
    WHERE catalog_file_event_public_id IS NULL;

ALTER TABLE movement ALTER COLUMN catalog_file_event_public_id SET NOT NULL;

ALTER TABLE movement
    ADD CONSTRAINT uk_movement_catalog_file_event_public_id UNIQUE (catalog_file_event_public_id);

COMMENT ON COLUMN movement.catalog_file_event_public_id IS
    'Identifies the CatalogFileEvent reserved for this operation; the event may not exist yet while the Movement is pending or may never exist if the operation is skipped or fails.';

-- ============================================================
-- What the caller asked for, said as such
-- ============================================================

-- Renamed because the rows now exist before anything happened: source_path and
-- target_path read as a record of a move, and until the operation settles they
-- are a request.
ALTER TABLE movement RENAME COLUMN source_path TO requested_source_path;
ALTER TABLE movement RENAME COLUMN target_path TO requested_target_path;

-- ============================================================
-- When it was prepared, and whether it ever moved
-- ============================================================

ALTER TABLE movement ADD COLUMN prepared_at TIMESTAMPTZ;

-- An existing row was written at the moment it moved, so that instant is the
-- best evidence of when it was prepared - it cannot have been later.
UPDATE movement SET prepared_at = moved_at AT TIME ZONE 'UTC' WHERE prepared_at IS NULL;

ALTER TABLE movement ALTER COLUMN prepared_at SET NOT NULL;

ALTER TABLE movement
    ALTER COLUMN moved_at TYPE TIMESTAMPTZ USING moved_at AT TIME ZONE 'UTC',
    ALTER COLUMN moved_at DROP NOT NULL,
    ALTER COLUMN moved_at DROP DEFAULT;

COMMENT ON COLUMN movement.prepared_at IS
    'When the operation was persisted, always before the file system was touched.';
COMMENT ON COLUMN movement.moved_at IS
    'When the file actually moved. Null until it does, and null forever for an operation that was skipped or failed.';

-- ============================================================
-- The state machine
-- ============================================================

-- UNDO_ERROR said that a movement had been undone badly, which put the outcome of
-- one operation on the row of another. A failed undo is now what it is: the
-- reversing movement failed, and the movement it was reversing never stopped
-- being MOVED.
UPDATE movement SET status = 'MOVED' WHERE status = 'UNDO_ERROR';

-- SIMULATED was never written by any code path - a dry run leaves a plan, not a
-- movement - so this removes a state rather than migrating one.
UPDATE movement SET status = 'SKIPPED' WHERE status = 'SIMULATED';

ALTER TABLE movement ADD CONSTRAINT ck_movement_status
    CHECK (status IN ('PENDING', 'MOVED', 'SKIPPED', 'ERROR', 'UNDONE'));

-- The timestamps and the state have to agree, and this is the one invariant the
-- database can hold on its own: a row claiming the file moved has to say when,
-- and a row that never moved must not.
ALTER TABLE movement ADD CONSTRAINT ck_movement_moved_at_matches_status
    CHECK ((status IN ('MOVED', 'UNDONE')) = (moved_at IS NOT NULL));

-- ============================================================
-- One operation per file per run
-- ============================================================

-- This is the key a retry prepares against: attempt two inserts nothing and reads
-- what attempt one wrote. Null catalog_file_id rows do not collide, which is
-- correct - those are movements whose file was purged, and they are history
-- rather than operations.
ALTER TABLE movement
    ADD CONSTRAINT uk_movement_execution_catalog_file UNIQUE (execution_id, catalog_file_id);

COMMENT ON TABLE movement IS
    'One operation the Nimbus issued over one catalogued file, persisted before the file system is touched so a retry can find it. Not a record of changes observed from outside - those are not operations.';