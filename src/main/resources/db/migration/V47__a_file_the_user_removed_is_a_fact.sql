-- Deleting a file for good is something that happened, and the catalog starts
-- saying so.
--
-- The lifecycle column already recorded the state: the user removed it. What
-- nothing recorded was the event of it becoming so - when, at whose request, and
-- on what grounds. It is the same omission the rest of this work has been
-- undoing, and here it is the one a person is most likely to ask about: "where
-- did that folder go" is answered by a history, and a column that only holds the
-- current answer cannot say when it changed or who changed it.
--
-- ============================================================
-- Why this is not a movement
-- ============================================================
--
-- Everything else the Explorer emits moves a file from one place to another, and
-- a movement is exactly that: a source, a target, and a status saying whether
-- the file arrived. A permanent delete has no target. The file did not go
-- somewhere the catalog could point at - it stopped existing - so what is
-- recorded is the fact and nothing else. Quarantine, which does move the file,
-- keeps its movement.
--
-- ============================================================
-- Which states can be decided away
-- ============================================================
--
-- ACTIVE and MISSING both can: the user is removing files they can see listed,
-- and a row the catalog had already lost track of is still one they asked to be
-- rid of. DELETED is left alone, so deleting a folder twice - a retry, a second
-- click - transitions nothing the second time and therefore records nothing.
-- Idempotent by construction rather than by checking first.

CREATE OR REPLACE FUNCTION mark_catalog_files_deleted(p_catalog_file_ids BIGINT[], p_event_public_ids UUID[],
        p_provenance catalog_fact_provenance)
RETURNS INT
LANGUAGE plpgsql
AS $$
DECLARE
    v_supplied INT;
    v_deleted INT;
BEGIN
    IF p_catalog_file_ids IS NULL OR p_event_public_ids IS NULL OR (p_provenance).occurred_at IS NULL
            OR (p_provenance).source IS NULL OR (p_provenance).evidence_kind IS NULL THEN
        RAISE EXCEPTION 'Removing files needs the files, their event identities and how it is known'
            USING ERRCODE = 'NB006';
    END IF;

    v_supplied := array_length(p_catalog_file_ids, 1);

    IF v_supplied IS DISTINCT FROM array_length(p_event_public_ids, 1) THEN
        RAISE EXCEPTION 'Every file needs exactly one event identity: % files, % identities', v_supplied,
            array_length(p_event_public_ids, 1) USING ERRCODE = 'NB006';
    END IF;

    IF v_supplied IS NULL THEN
        RETURN 0;
    END IF;

    WITH asked AS (
        SELECT i.catalog_file_id, i.event_id
        FROM unnest(p_catalog_file_ids, p_event_public_ids) AS i(catalog_file_id, event_id)
    ),
    removed AS (
        UPDATE catalog_file m
           SET lifecycle_status = 'DELETED',
               lifecycle_changed_at = (p_provenance).occurred_at,
               version = m.version + 1
          FROM asked a
         WHERE m.id = a.catalog_file_id
           AND m.lifecycle_status IN ('ACTIVE', 'MISSING')
        RETURNING m.id AS catalog_file_id, a.event_id
    ),
    recorded AS (
        INSERT INTO catalog_file_event (catalog_file_event_public_id, catalog_file_id, event_type, old_path,
                new_path, occurred_at, source, evidence_kind)
            SELECT r.event_id, r.catalog_file_id, 'DELETED', l.current_path, NULL,
                   (p_provenance).occurred_at, (p_provenance).source, (p_provenance).evidence_kind
            FROM removed r
            JOIN catalog_file_location l ON l.catalog_file_id = r.catalog_file_id
            RETURNING catalog_file_id
    )
    SELECT count(*) INTO v_deleted FROM removed;

    RETURN v_deleted;
END;
$$;

COMMENT ON FUNCTION mark_catalog_files_deleted(BIGINT[], UUID[], catalog_fact_provenance) IS
    'Records that files were removed at the user request, as one lifecycle transition and one fact each. Only files the catalog still counts as present or lost transition, so removing the same folder twice writes nothing the second time. The last known location is kept on the fact, because it is what a person asking where a file went is asking about.';