-- The proof travels as one thing, and the fact records what proved it.
--
-- Two changes that belong together. The door had reached nine parameters and was
-- about to take three more - the kind, scope and value of the identity that
-- established a change - and a positional list that long stops being a contract
-- and becomes a place for arguments to be swapped without the compiler noticing.
-- The three new ones also do not stand alone: they are, with the source and the
-- evidence, one answer to "how do we know this", asked identically by the scalar
-- door and the bulk one.
--
-- So they become a type. catalog_fact_provenance is what an observer knows about
-- a change it is reporting; the structural arguments - which file, from where, to
-- where, under which spelling rules - stay explicit, because those are what the
-- door actually reasons about. The door goes from nine parameters to seven while
-- absorbing three more, and both paths take the same one.
--
-- A composite and not JSON on purpose: the columns keep their types, a caller
-- cannot invent a field, and a missing one is an error at the call rather than a
-- null discovered later.

CREATE TYPE catalog_fact_provenance AS (
    occurred_at TIMESTAMPTZ,
    source TEXT,
    evidence_kind TEXT,
    filesystem_identity_kind TEXT,
    filesystem_identity_scope TEXT,
    filesystem_identity_value TEXT
);

COMMENT ON TYPE catalog_fact_provenance IS
    'What an observer knows about a change it reports: when it happened, what observed it, what proves the classification, and the filesystem identity behind that proof when there is one.';

-- ============================================================
-- Evidence that names a proof must carry it
-- ============================================================
--
-- A fact saying it was established by a filesystem identity, with no identity
-- recorded, is not a weaker fact - it is one whose stated reason cannot be
-- checked. The constraint names a single value rather than enumerating them, so
-- the open set the table deliberately keeps stays open: a new kind of proof needs
-- no migration, and only this one is pinned, because only this one makes a claim
-- about a payload that lives in the same row.

ALTER TABLE catalog_file_event ADD CONSTRAINT ck_catalog_file_event_identity_evidence
    CHECK (evidence_kind <> 'FILESYSTEM_IDENTITY_MATCH' OR filesystem_identity_value IS NOT NULL);

DROP FUNCTION rename_catalog_file(BIGINT, UUID, TEXT, TEXT, TEXT, TIMESTAMPTZ, TEXT, TEXT);
DROP FUNCTION move_catalog_file(BIGINT, UUID, TEXT, TEXT, TEXT, TIMESTAMPTZ, TEXT, TEXT);
DROP FUNCTION relocate_catalog_file(BIGINT, UUID, TEXT, TEXT, TEXT, TIMESTAMPTZ, TEXT, TEXT);
DROP FUNCTION relocate_catalog_folder_contents(BIGINT[], UUID[], TEXT, TEXT, TEXT, TIMESTAMPTZ, TEXT, TEXT);
DROP FUNCTION apply_location_change(BIGINT, UUID, TEXT, TEXT, TEXT, TEXT, TIMESTAMPTZ, TEXT, TEXT);

CREATE OR REPLACE FUNCTION apply_location_change(p_catalog_file_id BIGINT, p_catalog_file_event_public_id UUID,
        p_event_type TEXT, p_expected_old_path TEXT, p_new_path TEXT, p_path_flavor TEXT,
        p_provenance catalog_fact_provenance)
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
            OR p_new_path IS NULL OR p_path_flavor IS NULL OR (p_provenance).occurred_at IS NULL
            OR (p_provenance).source IS NULL OR (p_provenance).evidence_kind IS NULL THEN
        RAISE EXCEPTION 'A location change needs the file, the event, the destination, where it came from and how it is known'
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
                OR v_event.old_path IS DISTINCT FROM p_expected_old_path
                OR v_event.evidence_kind IS DISTINCT FROM (p_provenance).evidence_kind THEN
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

    INSERT INTO catalog_file_event (catalog_file_event_public_id, catalog_file_id, event_type, old_path,
            new_path, occurred_at, source, evidence_kind, filesystem_identity_kind, filesystem_identity_scope,
            filesystem_identity_value)
        VALUES (p_catalog_file_event_public_id, p_catalog_file_id, p_event_type, p_expected_old_path, p_new_path,
            (p_provenance).occurred_at, (p_provenance).source, (p_provenance).evidence_kind,
            (p_provenance).filesystem_identity_kind, (p_provenance).filesystem_identity_scope,
            (p_provenance).filesystem_identity_value)
        RETURNING id INTO v_event_id;

    -- path_key and current_folder are not assigned here: they are generated, and
    -- naming them would be this function claiming to know a rule the table owns.
    -- The identity travels to the placement as well as to the fact, and only when
    -- one was observed: an observer that could not supply one leaves the stored
    -- identity alone rather than erasing it. Nothing is lost by keeping a stale
    -- one, because the sequence number the operating system builds into a file id
    -- means it is never issued to another file - it goes inert, not wrong.
    UPDATE catalog_file_location
        SET current_path = p_new_path, path_flavor = p_path_flavor, updated_at = CURRENT_TIMESTAMP,
            filesystem_identity_kind = COALESCE((p_provenance).filesystem_identity_kind,
                filesystem_identity_kind),
            filesystem_identity_scope = CASE WHEN (p_provenance).filesystem_identity_kind IS NOT NULL
                THEN (p_provenance).filesystem_identity_scope ELSE filesystem_identity_scope END,
            filesystem_identity_value = CASE WHEN (p_provenance).filesystem_identity_kind IS NOT NULL
                THEN (p_provenance).filesystem_identity_value ELSE filesystem_identity_value END
        WHERE catalog_file_id = p_catalog_file_id;

    SELECT * INTO v_location FROM catalog_file_location WHERE catalog_file_id = p_catalog_file_id;

    RETURN QUERY SELECT v_event_id, v_location.current_path, v_location.path_key, v_location.current_folder,
            FALSE;
END;
$$;

COMMENT ON FUNCTION apply_location_change(BIGINT, UUID, TEXT, TEXT, TEXT, TEXT, catalog_fact_provenance) IS
    'Internal. The only way a known file changes location: records the fact and moves the projection in one transaction. Knows nothing about which feature asked, only what happened, what observed it and on what proof - call one of the wrappers.';

CREATE OR REPLACE FUNCTION rename_catalog_file(p_catalog_file_id BIGINT, p_catalog_file_event_public_id UUID,
        p_expected_old_path TEXT, p_new_path TEXT, p_path_flavor TEXT, p_provenance catalog_fact_provenance)
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
        p_expected_old_path, p_new_path, p_path_flavor, p_provenance);
END;
$$;

COMMENT ON FUNCTION rename_catalog_file(BIGINT, UUID, TEXT, TEXT, TEXT, catalog_fact_provenance) IS
    'A file keeps its folder and changes its name. Refuses a change that leaves the folder, which is a move.';

CREATE OR REPLACE FUNCTION move_catalog_file(p_catalog_file_id BIGINT, p_catalog_file_event_public_id UUID,
        p_expected_old_path TEXT, p_new_path TEXT, p_path_flavor TEXT, p_provenance catalog_fact_provenance)
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
        p_expected_old_path, p_new_path, p_path_flavor, p_provenance);
END;
$$;

COMMENT ON FUNCTION move_catalog_file(BIGINT, UUID, TEXT, TEXT, TEXT, catalog_fact_provenance) IS
    'A file goes to another folder, with or without a new name on arrival. Refuses a change that stays put, which is a rename.';

CREATE OR REPLACE FUNCTION relocate_catalog_file(p_catalog_file_id BIGINT, p_catalog_file_event_public_id UUID,
        p_expected_old_path TEXT, p_new_path TEXT, p_path_flavor TEXT, p_provenance catalog_fact_provenance)
RETURNS TABLE (event_id BIGINT, current_path TEXT, path_key TEXT, current_folder TEXT, replayed BOOLEAN)
LANGUAGE plpgsql
AS $$
BEGIN
    IF p_expected_old_path IS NULL THEN
        RAISE EXCEPTION 'A relocation has to say where the file was' USING ERRCODE = 'NB006';
    END IF;

    IF p_expected_old_path = p_new_path THEN
        RAISE EXCEPTION 'A relocation goes somewhere: % is where the file already is', p_new_path
            USING ERRCODE = 'NB007';
    END IF;

    RETURN QUERY SELECT * FROM apply_location_change(p_catalog_file_id, p_catalog_file_event_public_id,
        catalog_location_change_type(p_expected_old_path, p_new_path, p_path_flavor), p_expected_old_path,
        p_new_path, p_path_flavor, p_provenance);
END;
$$;

COMMENT ON FUNCTION relocate_catalog_file(BIGINT, UUID, TEXT, TEXT, TEXT, catalog_fact_provenance) IS
    'A file goes somewhere, and the fact is classified rather than asserted. For callers that compute a destination instead of naming an intention.';

CREATE OR REPLACE FUNCTION relocate_catalog_folder_contents(p_catalog_file_ids BIGINT[],
        p_catalog_file_event_public_ids UUID[], p_old_root TEXT, p_new_root TEXT, p_path_flavor TEXT,
        p_provenance catalog_fact_provenance)
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
            OR p_new_root IS NULL OR p_path_flavor IS NULL OR (p_provenance).occurred_at IS NULL
            OR (p_provenance).source IS NULL OR (p_provenance).evidence_kind IS NULL THEN
        RAISE EXCEPTION 'A relocation needs both roots, the files, their event identities, where it came from and how it is known'
            USING ERRCODE = 'NB006';
    END IF;

    -- A folder's identity is not any of its files' identities, and this writes one
    -- fact per file. Rather than quietly drop what it was handed, it refuses: when
    -- a folder relocation observed from outside arrives, what to record about the
    -- folder that proved it is a decision, not a default.
    IF (p_provenance).filesystem_identity_kind IS NOT NULL THEN
        RAISE EXCEPTION 'A folder relocation cannot carry a filesystem identity: it is one object and these are '
            'many facts' USING ERRCODE = 'NB006';
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
                new_path, occurred_at, source, evidence_kind)
            SELECT r.event_id, r.catalog_file_id,
                   catalog_location_change_type(r.old_path, r.new_path, p_path_flavor), r.old_path, r.new_path,
                   (p_provenance).occurred_at, (p_provenance).source, (p_provenance).evidence_kind
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

COMMENT ON FUNCTION relocate_catalog_folder_contents(BIGINT[], UUID[], TEXT, TEXT, TEXT, catalog_fact_provenance) IS
    'Every catalogued file under one folder moves to another, in one transaction, recording one fact per file. Serves both a folder rename and a folder move - which of the two it was is a statement about the folder, not about the files.';