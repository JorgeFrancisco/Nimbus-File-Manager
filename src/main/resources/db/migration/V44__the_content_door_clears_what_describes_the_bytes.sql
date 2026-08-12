-- Two things that describe the bytes were left behind by the content door.
--
-- mime_type and last_analysis live on catalog_file rather than in one of the
-- tables the change clears, and that is the whole of why they were missed. Both
-- are derived from the content:
--
--   mime_type says what the bytes are. A file called photo.jpg whose content is
--   replaced by a PNG kept on being described as a JPEG - and FileType.resolve
--   reads it, so the catalog could go on classifying the file by a fact about
--   bytes it no longer holds.
--
--   last_analysis says when the current content was analysed. After a change,
--   the current content has never been analysed, so any value there is false.
--   It was also actively harmful: the metadata rebuild the change queues selects
--   files "not analysed since" a cutoff, so a recent analysis of the previous
--   generation would filter the file out of the work meant to repair it. The
--   repair would have been queued and then skipped.
--
-- analysis_version is deliberately not touched. It is the version of the
-- algorithm, not of the content, and it stays true across an edit - which is the
-- distinction content_revision was introduced to stop conflating.

CREATE OR REPLACE FUNCTION apply_content_change(p_catalog_file_id BIGINT, p_expected_content_revision BIGINT,
        p_expected_sha256 TEXT, p_observed_sha256 TEXT, p_observed_size_bytes BIGINT,
        p_observed_modified_at TIMESTAMPTZ, p_catalog_file_event_public_id UUID,
        p_provenance catalog_fact_provenance)
RETURNS TABLE (outcome TEXT, content_revision BIGINT, event_id BIGINT)
LANGUAGE plpgsql
AS $$
DECLARE
    v_file catalog_file%ROWTYPE;
    v_event_id BIGINT;
    v_path TEXT;
BEGIN
    IF p_catalog_file_id IS NULL OR p_expected_content_revision IS NULL OR p_observed_sha256 IS NULL
            OR p_catalog_file_event_public_id IS NULL OR (p_provenance).occurred_at IS NULL
            OR (p_provenance).source IS NULL OR (p_provenance).evidence_kind IS NULL THEN
        RAISE EXCEPTION 'A content change needs the file, the state it was observed from, the new digest, the '
            'event identity and how it is known' USING ERRCODE = 'NB006';
    END IF;

    -- Locked before it is read, so the comparison below is against a row nobody
    -- else can move until this transaction ends. Without it two observers of one
    -- edit would both read the old digest and both believe they were first.
    SELECT * INTO v_file FROM catalog_file WHERE id = p_catalog_file_id FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'No catalog file %', p_catalog_file_id USING ERRCODE = 'NB001';
    END IF;

    -- Asked first, and on the digest rather than on the revision: whoever else
    -- applied this transition already advanced the counter, so comparing counters
    -- would read their success as our staleness. The question is whether the
    -- catalog already holds the bytes we are reporting.
    IF v_file.sha256 IS NOT DISTINCT FROM p_observed_sha256 THEN
        RETURN QUERY SELECT 'ALREADY_CONVERGED'::TEXT, v_file.content_revision, NULL::BIGINT;

        RETURN;
    END IF;

    IF v_file.content_revision <> p_expected_content_revision THEN
        RETURN QUERY SELECT 'STALE_OBSERVATION'::TEXT, v_file.content_revision, NULL::BIGINT;

        RETURN;
    END IF;

    IF v_file.sha256 IS DISTINCT FROM p_expected_sha256 THEN
        RETURN QUERY SELECT 'CONFLICT'::TEXT, v_file.content_revision, NULL::BIGINT;

        RETURN;
    END IF;

    SELECT current_path INTO v_path FROM catalog_file_location WHERE catalog_file_id = p_catalog_file_id;

    INSERT INTO catalog_file_event (catalog_file_event_public_id, catalog_file_id, event_type, old_path, new_path,
            occurred_at, source, evidence_kind, filesystem_identity_kind, filesystem_identity_scope,
            filesystem_identity_value)
        VALUES (p_catalog_file_event_public_id, p_catalog_file_id, 'CONTENT_CHANGED', v_path, v_path,
            (p_provenance).occurred_at, (p_provenance).source, (p_provenance).evidence_kind,
            (p_provenance).filesystem_identity_kind, (p_provenance).filesystem_identity_scope,
            (p_provenance).filesystem_identity_value)
        RETURNING id INTO v_event_id;

    -- The generation advances exactly here, and nowhere else. Everything computed
    -- from the previous one is, from this statement on, describing a file that no
    -- longer exists - which is what lets a late job be refused instead of
    -- resurrecting it.
    UPDATE catalog_file
        SET sha256 = p_observed_sha256,
            size_bytes = COALESCE(p_observed_size_bytes, size_bytes),
            modified_at = COALESCE(p_observed_modified_at, modified_at),
            content_revision = v_file.content_revision + 1,
            -- Derived from the bytes like everything else, and living on this row
            -- rather than in a table of its own - which is the only reason it was
            -- missed. A JPEG replaced by a PNG at the same path would go on being
            -- described as a JPEG, and the type of the file is resolved from it.
            mime_type = NULL,
            -- Cleared because it is the answer to "when was the current content
            -- analysed", and the current content has never been. Leaving the old
            -- instant would also defeat the rebuild this change queues: the
            -- selection asks for files not analysed since a cutoff, and a recent
            -- analysis of bytes that no longer exist would filter the file out of
            -- the very work meant to repair it.
            last_analysis = NULL
        WHERE id = p_catalog_file_id;

    -- The object holding the path may be a different one - an atomic replace
    -- swaps it without moving anything. Written only when the observer named an
    -- identity: one that could not is silent about this, and a stored identity is
    -- never erased on silence.
    IF (p_provenance).filesystem_identity_kind IS NOT NULL THEN
        UPDATE catalog_file_location
            SET filesystem_identity_kind = (p_provenance).filesystem_identity_kind,
                filesystem_identity_scope = (p_provenance).filesystem_identity_scope,
                filesystem_identity_value = (p_provenance).filesystem_identity_value,
                updated_at = CURRENT_TIMESTAMP
            WHERE catalog_file_id = p_catalog_file_id;
    END IF;

    RETURN QUERY SELECT 'APPLIED'::TEXT, v_file.content_revision + 1, v_event_id;
END;
$$;