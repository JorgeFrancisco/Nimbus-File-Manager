-- Changing where a file is stops being something each feature does its own way.
--
-- Every one of them used to write catalog_file.file_key and the location row by
-- hand, in whatever order, and none of them recorded that anything had happened.
-- Two of them could write the same path at the same time and the last one won,
-- silently. This is the only place a known file's location changes from now on,
-- and it is one transaction: the fact and the projection move together or
-- neither does.
--
-- It knows nothing about movements, executions, organization, quarantine or
-- undo. Those are reasons a file moves, not the moving itself, and they will
-- reach the catalog through wrappers of their own that end up here.

-- ============================================================
-- Error contract
-- ============================================================
--
-- Raised as SQLSTATE rather than only as text, so a caller decides what happened
-- by reading a code instead of matching a sentence somebody may reword. The
-- messages are for a log; these five characters are the contract:
--
--   NB001  catalog file not found
--   NB002  catalog file has no location to change
--   NB003  stale location - the file is not where the caller thought it was
--   NB004  destination already occupied by another present file
--   NB005  same event id, different payload
--   NB006  invalid argument
--   NB007  the change is not the one the capability that was called describes
--   NB008  invariant violation - more than one present file at one path

-- ============================================================
-- Shared by every capability that moves a file
-- ============================================================
--
-- These exist so the scalar door and the bulk one cannot drift. A rule written
-- twice is a rule that disagrees with itself eventually, and the two places it
-- would be written here are the two places a wrong answer is hardest to see.

-- The advisory-lock key for a place. Bounded to 64 bits by hashtextextended
-- rather than the 32 of hashtext: a collision costs two unrelated places a
-- shared queue, never a wrong answer, but there is no reason to make it likely.
-- The flavor is part of the key because two spellings read under different rules
-- can produce the same text while naming folders on different machines.
CREATE OR REPLACE FUNCTION catalog_place_lock_key(flavor TEXT, place_key TEXT)
RETURNS BIGINT
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $$
    SELECT hashtextextended(flavor || ':' || place_key, 0);
$$;

-- Which of the two things a change is. Textual and total: same folder means the
-- file was renamed, a different folder means it went somewhere, and the caller
-- naming the wrong one is what the public wrappers refuse.
CREATE OR REPLACE FUNCTION catalog_location_change_type(old_path TEXT, new_path TEXT, flavor TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $$
    SELECT CASE
        WHEN canonicalize_catalog_path(parent_catalog_path(old_path, flavor), flavor)
             IS DISTINCT FROM canonicalize_catalog_path(parent_catalog_path(new_path, flavor), flavor)
        THEN 'MOVED'
        ELSE 'RENAMED'
    END;
$$;

-- Internal by convention rather than by grant: the wrappers below are what a
-- feature calls, and each of them exists to refuse a change that is not the one
-- it names. Calling this directly bypasses that, which is the whole reason the
-- wrappers are separate functions.
CREATE OR REPLACE FUNCTION apply_location_change(p_catalog_file_id BIGINT, p_catalog_file_event_public_id UUID,
        p_event_type TEXT, p_expected_old_path TEXT, p_new_path TEXT, p_path_flavor TEXT,
        p_occurred_at TIMESTAMPTZ, p_source TEXT)
RETURNS TABLE (event_id BIGINT, current_path TEXT, path_key TEXT, current_folder TEXT, replayed BOOLEAN)
LANGUAGE plpgsql
AS $$
DECLARE
    v_event catalog_file_event%ROWTYPE;
    v_location catalog_file_location%ROWTYPE;
    v_new_key TEXT;
    v_occupants INT;
    v_event_id BIGINT;
BEGIN
    IF p_catalog_file_id IS NULL OR p_catalog_file_event_public_id IS NULL OR p_event_type IS NULL
            OR p_new_path IS NULL OR p_path_flavor IS NULL OR p_occurred_at IS NULL OR p_source IS NULL THEN
        RAISE EXCEPTION 'A location change needs the file, the event, the destination and where it came from'
            USING ERRCODE = 'NB006';
    END IF;

    v_new_key := canonicalize_catalog_path(p_new_path, p_path_flavor);

    -- The destination is locked before it is examined, and the lock is over the
    -- place rather than over a row: the row a second transaction is about to write
    -- there does not exist yet, so there is nothing for it to wait on. The key
    -- carries the flavor because two spellings read under different rules can
    -- produce the same text while naming folders on different machines, and those
    -- must not queue behind each other by accident.
    --
    -- Two locks, and always folder before path. The folder one is what a bulk
    -- relocation of a whole directory holds - it cannot hold one per file without
    -- exhausting the lock table - so taking it here is the only thing that makes
    -- the two capabilities see each other at all. The order is fixed rather than
    -- convenient: it is what stops the two from deadlocking.
    PERFORM pg_advisory_xact_lock(catalog_place_lock_key(p_path_flavor,
        canonicalize_catalog_path(parent_catalog_path(p_new_path, p_path_flavor), p_path_flavor)));
    PERFORM pg_advisory_xact_lock(catalog_place_lock_key(p_path_flavor, v_new_key));

    -- Idempotency is settled before anything about the current state is read, and
    -- that order is the point rather than an accident of layout: a caller retrying
    -- after a lost response describes the world as it was before its own successful
    -- write. Judged against the world as it is now, that reads as a stale caller,
    -- and the retry would be refused for having worked.
    SELECT * INTO v_event FROM catalog_file_event WHERE catalog_file_event_public_id = p_catalog_file_event_public_id;

    IF FOUND THEN
        IF v_event.catalog_file_id <> p_catalog_file_id OR v_event.event_type <> p_event_type
                OR v_event.new_path IS DISTINCT FROM p_new_path
                OR v_event.old_path IS DISTINCT FROM p_expected_old_path THEN
            RAISE EXCEPTION 'Event % was already recorded describing something else', p_catalog_file_event_public_id
                USING ERRCODE = 'NB005';
        END IF;

        SELECT * INTO v_location FROM catalog_file_location WHERE catalog_file_id = p_catalog_file_id;

        RETURN QUERY SELECT v_event.id, v_location.current_path, v_location.path_key,
                v_location.current_folder, TRUE;

        RETURN;
    END IF;

    PERFORM 1 FROM catalog_file WHERE id = p_catalog_file_id FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'No catalog file %', p_catalog_file_id USING ERRCODE = 'NB001';
    END IF;

    SELECT * INTO v_location FROM catalog_file_location
        WHERE catalog_file_id = p_catalog_file_id FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Catalog file % has no location to change', p_catalog_file_id USING ERRCODE = 'NB002';
    END IF;

    -- Compared as written rather than canonically, and deliberately: a caller that
    -- believes the file is at one spelling and finds it at another is working from
    -- an outdated view even when the two spellings mean the same place.
    IF p_expected_old_path IS NOT NULL AND v_location.current_path <> p_expected_old_path THEN
        RAISE EXCEPTION 'Catalog file % is at % and not at %', p_catalog_file_id, v_location.current_path,
            p_expected_old_path USING ERRCODE = 'NB003';
    END IF;

    -- Only a file that is actually there occupies the place. One that went missing
    -- keeps its path as the last thing known about it, and a removed one is out of
    -- the library's namespace - neither is in the way of an arrival. The file being
    -- moved cannot block itself, which is what makes a case-only rename possible.
    SELECT count(*) INTO v_occupants
        FROM catalog_file_location l
        JOIN catalog_file m ON m.id = l.catalog_file_id
        WHERE l.path_flavor = p_path_flavor
            AND l.path_key = v_new_key
            AND m.lifecycle_status = 'ACTIVE'
            AND l.catalog_file_id <> p_catalog_file_id;

    IF v_occupants > 1 THEN
        RAISE EXCEPTION 'More than one present catalog file already occupies %', p_new_path
            USING ERRCODE = 'NB008';
    END IF;

    IF v_occupants = 1 THEN
        RAISE EXCEPTION 'Another present catalog file already occupies %', p_new_path USING ERRCODE = 'NB004';
    END IF;

    INSERT INTO catalog_file_event (catalog_file_event_public_id, catalog_file_id, event_type, old_path, new_path, occurred_at,
            source)
        VALUES (p_catalog_file_event_public_id, p_catalog_file_id, p_event_type, p_expected_old_path, p_new_path,
            p_occurred_at, p_source)
        RETURNING id INTO v_event_id;

    -- path_key and current_folder are not assigned here: they are generated, and
    -- naming them would be this function claiming to know a rule the table owns.
    UPDATE catalog_file_location
        SET current_path = p_new_path, path_flavor = p_path_flavor, updated_at = CURRENT_TIMESTAMP
        WHERE catalog_file_id = p_catalog_file_id;

    SELECT * INTO v_location FROM catalog_file_location WHERE catalog_file_id = p_catalog_file_id;

    RETURN QUERY SELECT v_event_id, v_location.current_path, v_location.path_key, v_location.current_folder,
            FALSE;
END;
$$;

COMMENT ON FUNCTION apply_location_change(BIGINT, UUID, TEXT, TEXT, TEXT, TEXT, TIMESTAMPTZ, TEXT) IS
    'Internal. The only way a known file changes location: records the fact and moves the projection in one transaction. Knows nothing about why the file moved - call one of the wrappers.';

-- ============================================================
-- The two things a caller can mean
-- ============================================================
--
-- Which of the two a change is stays the caller's statement rather than
-- something re-derived here, so the rule lives in one place in the application.
-- What these add is a refusal: each rejects a change that is not the one it
-- names, so the two can never quietly swap and a bug in a caller surfaces as an
-- error rather than as a wrong event type in the history.

CREATE OR REPLACE FUNCTION rename_catalog_file(p_catalog_file_id BIGINT, p_catalog_file_event_public_id UUID,
        p_expected_old_path TEXT, p_new_path TEXT, p_path_flavor TEXT, p_occurred_at TIMESTAMPTZ, p_source TEXT)
RETURNS TABLE (event_id BIGINT, current_path TEXT, path_key TEXT, current_folder TEXT, replayed BOOLEAN)
LANGUAGE plpgsql
AS $$
BEGIN
    IF p_expected_old_path IS NULL THEN
        RAISE EXCEPTION 'A rename has to say what the file was called' USING ERRCODE = 'NB006';
    END IF;

    IF catalog_location_change_type(p_expected_old_path, p_new_path, p_path_flavor) <> 'RENAMED' THEN
        RAISE EXCEPTION 'A rename stays in its folder: % is not in the folder of %', p_new_path,
            p_expected_old_path USING ERRCODE = 'NB007';
    END IF;

    -- Compared as written, so changing only the case of a name on Windows is a
    -- rename even though the canonical key does not move. The file did not go
    -- anywhere and the user did rename it.
    IF p_expected_old_path = p_new_path THEN
        RAISE EXCEPTION 'A rename changes the name' USING ERRCODE = 'NB007';
    END IF;

    RETURN QUERY SELECT * FROM apply_location_change(p_catalog_file_id, p_catalog_file_event_public_id, 'RENAMED',
        p_expected_old_path, p_new_path, p_path_flavor, p_occurred_at, p_source);
END;
$$;

COMMENT ON FUNCTION rename_catalog_file(BIGINT, UUID, TEXT, TEXT, TEXT, TIMESTAMPTZ, TEXT) IS
    'A file keeps its folder and changes its name. Refuses a change that leaves the folder, which is a move.';

CREATE OR REPLACE FUNCTION move_catalog_file(p_catalog_file_id BIGINT, p_catalog_file_event_public_id UUID,
        p_expected_old_path TEXT, p_new_path TEXT, p_path_flavor TEXT, p_occurred_at TIMESTAMPTZ, p_source TEXT)
RETURNS TABLE (event_id BIGINT, current_path TEXT, path_key TEXT, current_folder TEXT, replayed BOOLEAN)
LANGUAGE plpgsql
AS $$
BEGIN
    IF p_expected_old_path IS NULL THEN
        RAISE EXCEPTION 'A move has to say where the file was' USING ERRCODE = 'NB006';
    END IF;

    -- A move that also renames is still one move: the file went somewhere else, and
    -- what it is called there is part of where it now is.
    IF catalog_location_change_type(p_expected_old_path, p_new_path, p_path_flavor) <> 'MOVED' THEN
        RAISE EXCEPTION 'A move leaves its folder: % is already in the folder of %', p_new_path,
            p_expected_old_path USING ERRCODE = 'NB007';
    END IF;

    RETURN QUERY SELECT * FROM apply_location_change(p_catalog_file_id, p_catalog_file_event_public_id, 'MOVED',
        p_expected_old_path, p_new_path, p_path_flavor, p_occurred_at, p_source);
END;
$$;

COMMENT ON FUNCTION move_catalog_file(BIGINT, UUID, TEXT, TEXT, TEXT, TIMESTAMPTZ, TEXT) IS
    'A file goes to another folder, with or without a new name on arrival. Refuses a change that stays put, which is a rename.';