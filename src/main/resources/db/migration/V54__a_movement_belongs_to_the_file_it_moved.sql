-- A movement stops outliving the file it moved.
--
-- The column detached instead of following: deleting a catalogue row set
-- movement.catalog_file_id to null and left the row behind, on the reasoning
-- that an operation is history and history should survive. Hard purge is not
-- that. When the bytes are destroyed on purpose there is no file to have a
-- history of, and what stayed behind was a row naming paths that lead nowhere,
-- readable by nobody, attached to an execution that already carries its own
-- counters.
--
-- Execution is the exception and keeps everything: it aggregates thousands of
-- files, has a retention of its own, and its totals are stamped on the
-- execution row rather than counted from here. What shrinks after a purge is
-- the movement list a finished execution can still show - deliberately, because
-- those rows were the file's, not the run's.
--
-- Legacy rows first. Anything already detached belongs to a file destroyed
-- under the old rule, so it goes; but a detached row that still looks like a
-- live quarantine would be a file waiting on disk to be brought back, and this
-- refuses to delete one. There is no known way to produce one - the three
-- purges all refuse a file held in quarantine - and if some older version did,
-- the person running the upgrade is told instead of finding out later.

DO $$
DECLARE
    v_recoverable INT;
BEGIN
    SELECT count(*) INTO v_recoverable
      FROM movement
     WHERE catalog_file_id IS NULL
       AND status = 'MOVED'
       AND reason IN ('DUPLICATE_QUARANTINED', 'CONVERTED_QUARANTINED', 'USER_QUARANTINED');

    IF v_recoverable > 0 THEN
        RAISE EXCEPTION 'Refusing to drop % detached movement(s) that still describe a file held in quarantine: '
            'each one is the only record of where that file came from', v_recoverable USING ERRCODE = 'NB007';
    END IF;
END
$$;

DELETE FROM movement WHERE catalog_file_id IS NULL;

ALTER TABLE movement ALTER COLUMN catalog_file_id SET NOT NULL;

ALTER TABLE movement DROP CONSTRAINT fk_movement_file;

ALTER TABLE movement ADD CONSTRAINT fk_movement_file FOREIGN KEY (catalog_file_id)
    REFERENCES catalog_file(id) ON DELETE CASCADE;

COMMENT ON CONSTRAINT fk_movement_file ON movement IS
    'A movement is an operation on one file and goes when the file is destroyed for good. The execution it belongs to survives: it aggregates many files and keeps its own totals.';