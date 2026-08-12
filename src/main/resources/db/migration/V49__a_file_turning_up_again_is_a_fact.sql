-- A file the catalog had lost turning up again is something that happened.
--
-- Going missing became a fact when the reconciliation learned to record it. The
-- other half never did: a walk that meets the file again promotes the row back
-- to ACTIVE and says nothing, so a timeline ends at "missing" while the row
-- reads present. Somebody reading that history sees a file that was lost and no
-- account of it being found - which is exactly the moment they were trying to
-- understand.
--
-- ============================================================
-- Why this one only writes the fact
-- ============================================================
--
-- Every other lifecycle door here changes the state and records it in one
-- statement, because it is the door that decides. This one is not the decider:
-- the walk that saw the file has already brought the entry back, in the
-- transaction this is called from, and it is the only thing in a position to
-- know that the file it was holding is the file the catalog meant. Re-deciding
-- from the row would find it already promoted and conclude nothing happened.
--
-- So the caller performs the transition and this records what it was. They
-- commit together or not at all, which is the property that mattered.
--
-- Recording it twice is not possible in practice for the reason a repeat never
-- reaches here: the second walk finds the entry already present and reports no
-- reappearance to record.

CREATE OR REPLACE FUNCTION record_catalog_files_present(p_catalog_file_ids BIGINT[], p_event_public_ids UUID[],
        p_provenance catalog_fact_provenance)
RETURNS INT
LANGUAGE plpgsql
AS $$
DECLARE
    v_supplied INT;
    v_recorded INT;
BEGIN
    IF p_catalog_file_ids IS NULL OR p_event_public_ids IS NULL OR (p_provenance).occurred_at IS NULL
            OR (p_provenance).source IS NULL OR (p_provenance).evidence_kind IS NULL THEN
        RAISE EXCEPTION 'Recording a file as present needs the files, their event identities and how it is known'
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

    -- The path goes in as where the file is, not where it was: this fact is about
    -- the catalog finding it, and the place it was found is the whole of what was
    -- observed.
    WITH recorded AS (
        INSERT INTO catalog_file_event (catalog_file_event_public_id, catalog_file_id, event_type, old_path,
                new_path, occurred_at, source, evidence_kind)
            SELECT i.event_id, i.catalog_file_id, 'REAPPEARED', NULL, l.current_path,
                   (p_provenance).occurred_at, (p_provenance).source, (p_provenance).evidence_kind
            FROM unnest(p_catalog_file_ids, p_event_public_ids) AS i(catalog_file_id, event_id)
            JOIN catalog_file_location l ON l.catalog_file_id = i.catalog_file_id
        ON CONFLICT (catalog_file_event_public_id) DO NOTHING
            RETURNING catalog_file_id
    )
    SELECT count(*) INTO v_recorded FROM recorded;

    RETURN v_recorded;
END;
$$;

COMMENT ON FUNCTION record_catalog_files_present(BIGINT[], UUID[], catalog_fact_provenance) IS
    'Records that files the catalog had lost were met again, one fact each, in the transaction that promoted them. Unlike the other lifecycle doors this one does not decide the transition: the walk that saw the file already did, and is the only thing that could have.';