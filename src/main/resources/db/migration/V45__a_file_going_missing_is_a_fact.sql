-- A file going missing is something that happened, and the catalog starts saying so.
--
-- The lifecycle column already recorded the state: the file is not there. What
-- nothing recorded was the event of it becoming so - when the catalog concluded
-- it, what had been looking, and on what grounds. That is the same omission the
-- rest of this work has been undoing, and it is the more visible one here:
-- "where did my photo go" is answered by a history, and a column that only ever
-- holds the current answer cannot tell anyone when it changed.
--
-- It is not an operation of ours. Nothing moved the file - a pass over the disk
-- looked where the catalog said it would be and it was not there - so there is
-- no movement, and the evidence is exactly that: the path held nothing.
--
-- ============================================================
-- Why one statement rather than a loop
-- ============================================================
--
-- A drive that goes offline takes a hundred thousand files with it, and each one
-- of them is its own fact: a fact per file is what makes a history readable, and
-- one row saying "a hundred thousand files went missing" answers no question
-- anybody asks about a photo. So the transition and its facts are one statement
-- over arrays, the way a folder relocation already writes one fact per file it
-- moves.
--
-- The identities are minted by the caller and travel in, for the same reason
-- they do there: a UUIDv7 carries the time it was created, and generating them
-- here would produce v4 - the ordering the rest of the catalog relies on, thrown
-- away at the last step.
--
-- ============================================================
-- Why this is idempotent without trying to be
-- ============================================================
--
-- The update selects only files that are ACTIVE, and the facts are written from
-- what it actually changed. A second pass over a library whose drive is still
-- offline finds them already missing, changes nothing, and therefore records
-- nothing - not because it checks first, but because there was no transition to
-- record. A file the user deliberately removed is never touched at all: DELETED
-- is a decision, and a walk of the disk does not get to overrule it.

CREATE OR REPLACE FUNCTION mark_catalog_files_missing(p_catalog_file_ids BIGINT[], p_event_public_ids UUID[],
        p_provenance catalog_fact_provenance)
RETURNS INT
LANGUAGE plpgsql
AS $$
DECLARE
    v_supplied INT;
    v_marked INT;
BEGIN
    IF p_catalog_file_ids IS NULL OR p_event_public_ids IS NULL OR (p_provenance).occurred_at IS NULL
            OR (p_provenance).source IS NULL OR (p_provenance).evidence_kind IS NULL THEN
        RAISE EXCEPTION 'Marking files missing needs the files, their event identities and how it is known'
            USING ERRCODE = 'NB006';
    END IF;

    v_supplied := array_length(p_catalog_file_ids, 1);

    IF v_supplied IS DISTINCT FROM array_length(p_event_public_ids, 1) THEN
        RAISE EXCEPTION 'Every file needs exactly one event identity: % files, % identities', v_supplied,
            array_length(p_event_public_ids, 1) USING ERRCODE = 'NB006';
    END IF;

    -- Nothing to mark is an ordinary answer: most passes agree with the disk.
    IF v_supplied IS NULL THEN
        RETURN 0;
    END IF;

    WITH asked AS (
        SELECT i.catalog_file_id, i.event_id
        FROM unnest(p_catalog_file_ids, p_event_public_ids) AS i(catalog_file_id, event_id)
    ),
    -- Only a real transition. An already-missing file keeps the timestamp it got
    -- when it first went missing, because that is the clock the catalog purge
    -- counts from - restarting it on every pass would keep a lost file forever.
    gone AS (
        UPDATE catalog_file m
           SET lifecycle_status = 'MISSING',
               lifecycle_changed_at = (p_provenance).occurred_at,
               version = m.version + 1
          FROM asked a
         WHERE m.id = a.catalog_file_id
           AND m.lifecycle_status = 'ACTIVE'
        RETURNING m.id AS catalog_file_id, a.event_id
    ),
    recorded AS (
        INSERT INTO catalog_file_event (catalog_file_event_public_id, catalog_file_id, event_type, old_path,
                new_path, occurred_at, source, evidence_kind)
            SELECT g.event_id, g.catalog_file_id, 'MISSING', l.current_path, NULL,
                   (p_provenance).occurred_at, (p_provenance).source, (p_provenance).evidence_kind
            FROM gone g
            JOIN catalog_file_location l ON l.catalog_file_id = g.catalog_file_id
            RETURNING catalog_file_id
    )
    SELECT count(*) INTO v_marked FROM gone;

    RETURN v_marked;
END;
$$;

COMMENT ON FUNCTION mark_catalog_files_missing(BIGINT[], UUID[], catalog_fact_provenance) IS
    'Records that files the catalog believed present are not where it said, as one lifecycle transition and one fact each. Only ACTIVE files transition, so a repeated pass over a library that is still offline writes nothing; DELETED is a decision and is never overruled here. The last known location is deliberately kept - it is what the catalog looks for the file by, and what a screen shows when asked where it used to be.';