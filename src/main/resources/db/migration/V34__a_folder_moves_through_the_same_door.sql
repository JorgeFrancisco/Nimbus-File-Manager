-- Moving a folder stops being a bulk UPDATE nobody records.
--
-- It used to rewrite catalog_file.file_key and catalog_file_location in two
-- native statements, write a generated column, take no lock, and leave no trace
-- that fifty thousand files had changed place. Every guarantee the single-file
-- door earns - the fact recorded, the destination checked, the whole thing
-- atomic - was simply absent from the operation that moves the most files.
--
-- The obvious repair, calling the scalar door once per file, is not one: fifty
-- thousand round trips is not a repair, it is a different outage. So this is the
-- same guarantees expressed set-based, sharing the scalar door's rules through
-- the helpers both call rather than by restating them.
--
-- One operation on a folder, N facts about files. A folder is not a thing the
-- catalog knows; what it knows are files, and each of them moved.

CREATE OR REPLACE FUNCTION relocate_catalog_folder_contents(p_catalog_file_ids BIGINT[],
        p_catalog_file_event_public_ids UUID[], p_old_root TEXT, p_new_root TEXT, p_path_flavor TEXT,
        p_occurred_at TIMESTAMPTZ, p_source TEXT)
RETURNS TABLE (files_relocated INT, replayed BOOLEAN)
LANGUAGE plpgsql
AS $$
DECLARE
    v_old_key TEXT;
    v_new_key TEXT;
    v_supplied INT;
    v_known INT;
    v_under_root INT;
    v_occupied INT;
    v_moved INT;
    v_folder BIGINT;
BEGIN
    IF p_catalog_file_ids IS NULL OR p_catalog_file_event_public_ids IS NULL OR p_old_root IS NULL
            OR p_new_root IS NULL OR p_path_flavor IS NULL OR p_occurred_at IS NULL OR p_source IS NULL THEN
        RAISE EXCEPTION 'A relocation needs both roots, the files, their event identities and where it came from'
            USING ERRCODE = 'NB006';
    END IF;

    v_supplied := array_length(p_catalog_file_ids, 1);

    IF v_supplied IS DISTINCT FROM array_length(p_catalog_file_event_public_ids, 1) THEN
        RAISE EXCEPTION 'Every file needs exactly one event identity: % files, % identities', v_supplied,
            array_length(p_catalog_file_event_public_ids, 1) USING ERRCODE = 'NB006';
    END IF;

    -- Nothing to move is a legitimate answer, not an error: a folder with no
    -- catalogued file under it is renamed on disk and the catalog has nothing to
    -- say about it.
    IF v_supplied IS NULL THEN
        RETURN QUERY SELECT 0, FALSE;

        RETURN;
    END IF;

    IF EXISTS (SELECT 1 FROM unnest(p_catalog_file_event_public_ids) AS e(id)
            GROUP BY e.id HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'Two files were given the same event identity' USING ERRCODE = 'NB006';
    END IF;

    v_old_key := canonicalize_catalog_path(p_old_root, p_path_flavor);
    v_new_key := canonicalize_catalog_path(p_new_root, p_path_flavor);

    IF v_old_key = v_new_key AND p_old_root = p_new_root THEN
        RAISE EXCEPTION 'A relocation goes somewhere: % is where the folder already is', p_new_root
            USING ERRCODE = 'NB007';
    END IF;

    -- The destination root first, and before anything is read: it is the one lock
    -- key both this call and any competing relocation into the same folder can
    -- name from their arguments alone, without having read a single row.
    PERFORM pg_advisory_xact_lock(catalog_place_lock_key(p_path_flavor, v_new_key));

    -- ------------------------------------------------------------
    -- Idempotency, before anything about the current state is read
    -- ------------------------------------------------------------
    --
    -- Same reasoning as the scalar door, and more consequential here: a caller
    -- retrying after a lost response describes a world its own successful write
    -- already left behind. Judged against the world as it is now, every one of
    -- fifty thousand files would look stale.
    --
    -- All or none. A batch where some identities were recorded and some were not
    -- is not a retry of anything - it is two different operations wearing one
    -- name, and continuing would decide silently which half wins.
    SELECT count(*) INTO v_known FROM catalog_file_event
        WHERE catalog_file_event_public_id = ANY(p_catalog_file_event_public_ids);

    IF v_known = v_supplied THEN
        IF EXISTS (
            SELECT 1
            FROM unnest(p_catalog_file_ids, p_catalog_file_event_public_ids) AS i(catalog_file_id, event_id)
            JOIN catalog_file_event e ON e.catalog_file_event_public_id = i.event_id
            WHERE e.catalog_file_id <> i.catalog_file_id OR e.source IS DISTINCT FROM p_source
        ) THEN
            RAISE EXCEPTION 'This batch of event identities was already recorded describing something else'
                USING ERRCODE = 'NB005';
        END IF;

        RETURN QUERY SELECT v_supplied, TRUE;

        RETURN;
    END IF;

    IF v_known > 0 THEN
        RAISE EXCEPTION 'Of % event identities, % were already recorded and % were not', v_supplied, v_known,
            v_supplied - v_known USING ERRCODE = 'NB005';
    END IF;

    -- ------------------------------------------------------------
    -- Locks, in one deterministic order
    -- ------------------------------------------------------------
    --
    -- Every distinct destination folder, ascending by key. Ascending because two
    -- relocations whose destinations overlap must queue rather than deadlock, and
    -- by folder rather than by file because a lock per file would exhaust the lock
    -- table on the batch sizes this exists for - while the scalar door takes the
    -- folder lock too, which is what makes a single-file move into this tree wait
    -- for us and not slip between the check and the write.
    FOR v_folder IN
        SELECT DISTINCT catalog_place_lock_key(p_path_flavor,
                canonicalize_catalog_path(parent_catalog_path(
                    p_new_root || substr(l.current_path, length(p_old_root) + 1), p_path_flavor), p_path_flavor))
        FROM catalog_file_location l
        WHERE l.catalog_file_id = ANY(p_catalog_file_ids)
        ORDER BY 1
    LOOP
        PERFORM pg_advisory_xact_lock(v_folder);
    END LOOP;

    -- Then the rows themselves, ascending by id - the same order the scalar door
    -- locks its single row in, so the two orders can never cross.
    PERFORM 1 FROM catalog_file WHERE id = ANY(p_catalog_file_ids) ORDER BY id FOR UPDATE;

    -- ------------------------------------------------------------
    -- The caller's view of the folder has to still be true
    -- ------------------------------------------------------------
    --
    -- Both directions are checked. Every file the caller named must be under the
    -- old root, and every file under the old root must have been named - a file
    -- that arrived while the caller was reading would otherwise be left behind,
    -- which is the partial move this function exists to make impossible.
    SELECT count(*) INTO v_under_root
        FROM catalog_file_location l
        WHERE l.path_flavor = p_path_flavor
          AND starts_with(l.path_key, v_old_key || '/');

    IF v_under_root <> v_supplied OR EXISTS (
        SELECT 1 FROM catalog_file_location l
        WHERE l.catalog_file_id = ANY(p_catalog_file_ids)
          AND (l.path_flavor <> p_path_flavor
               OR NOT starts_with(l.path_key, v_old_key || '/')
               OR canonicalize_catalog_path(substr(l.current_path, 1, length(p_old_root)), p_path_flavor)
                  IS DISTINCT FROM v_old_key)
    ) THEN
        RAISE EXCEPTION 'The folder % no longer holds exactly the % file(s) this was asked for', p_old_root,
            v_supplied USING ERRCODE = 'NB003';
    END IF;

    -- ------------------------------------------------------------
    -- Every destination free, or none of it happens
    -- ------------------------------------------------------------
    --
    -- Only a file that is actually there occupies a place; one that went missing
    -- from it is remembered rather than in the way. Checked for the whole batch
    -- before a single row is written, because moving nine thousand files and
    -- failing on the last leaves a folder that is half in two places.
    SELECT count(*) INTO v_occupied
        FROM catalog_file_location moving
        JOIN catalog_file_location taken
          ON taken.path_flavor = p_path_flavor
         AND taken.path_key = canonicalize_catalog_path(
                 p_new_root || substr(moving.current_path, length(p_old_root) + 1), p_path_flavor)
        JOIN catalog_file m ON m.id = taken.catalog_file_id
        WHERE moving.catalog_file_id = ANY(p_catalog_file_ids)
          AND m.lifecycle_status = 'ACTIVE'
          AND NOT (taken.catalog_file_id = ANY(p_catalog_file_ids));

    IF v_occupied > 0 THEN
        RAISE EXCEPTION 'Relocating % to % would land on % file(s) already there', p_old_root, p_new_root,
            v_occupied USING ERRCODE = 'NB004';
    END IF;

    -- ------------------------------------------------------------
    -- The facts, then the placements
    -- ------------------------------------------------------------
    --
    -- Each file gets its own fact, classified by the same rule the scalar door
    -- states: what the user did was rename a folder, but what happened to a file
    -- inside it is that its folder changed, which is a move. A folder rename that
    -- only changes case is the exception the rule already covers - the folder is
    -- canonically the same one, so those files were renamed.
    WITH relocation AS (
        SELECT i.catalog_file_id,
               i.event_id,
               l.current_path AS old_path,
               p_new_root || substr(l.current_path, length(p_old_root) + 1) AS new_path
        FROM unnest(p_catalog_file_ids, p_catalog_file_event_public_ids) AS i(catalog_file_id, event_id)
        JOIN catalog_file_location l ON l.catalog_file_id = i.catalog_file_id
    ),
    recorded AS (
        INSERT INTO catalog_file_event (catalog_file_event_public_id, catalog_file_id, event_type, old_path,
                new_path, occurred_at, source)
            SELECT r.event_id, r.catalog_file_id,
                   catalog_location_change_type(r.old_path, r.new_path, p_path_flavor), r.old_path, r.new_path,
                   p_occurred_at, p_source
            FROM relocation r
            RETURNING catalog_file_id
    ),
    -- path_key and current_folder are left alone: they are generated, and naming
    -- them would be this function claiming to know a rule the table owns.
    placed AS (
        UPDATE catalog_file_location l
           SET current_path = r.new_path, path_flavor = p_path_flavor, updated_at = CURRENT_TIMESTAMP
          FROM relocation r
         WHERE l.catalog_file_id = r.catalog_file_id
        RETURNING l.catalog_file_id
    )
    SELECT count(*) INTO v_moved FROM placed;

    RETURN QUERY SELECT v_moved, FALSE;
END;
$$;

COMMENT ON FUNCTION relocate_catalog_folder_contents(BIGINT[], UUID[], TEXT, TEXT, TEXT, TIMESTAMPTZ, TEXT) IS
    'Every catalogued file under one folder moves to another, in one transaction, recording one fact per file. Serves both a folder rename and a folder move - which of the two it was is a statement about the folder, not about the files.';