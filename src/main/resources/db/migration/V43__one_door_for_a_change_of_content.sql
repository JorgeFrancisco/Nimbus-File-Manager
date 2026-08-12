-- A change of content goes through one door, and the door arbitrates the race.
--
-- Two things can notice that a file's bytes are not what the catalog says: the
-- watcher, because the operating system told it, and a scan, because the size or
-- the timestamp moved. They can notice the same edit at the same moment, and
-- they reach the database independently. Read-then-write in Java would let both
-- pass the check and both advance the revision, leaving two facts and a counter
-- that moved twice for one edit.
--
-- So the comparison and the write are one statement, and the database decides.
-- What the caller says is the state it observed the change *from*; if the
-- catalog is no longer in that state, the caller does not get to overwrite it -
-- it gets told which of four situations it is in:
--
--   APPLIED            the catalog was where the caller thought, and moved.
--   ALREADY_CONVERGED  someone else already applied this exact transition.
--                      Nothing written, no second fact, revision not advanced.
--   STALE_OBSERVATION  the catalog has moved on to something the caller has not
--                      seen. Its reading is old; it has to look again.
--   CONFLICT           the revision is the one the caller expected but the digest
--                      is not, which is not lag - it is a disagreement about what
--                      that revision holds.
--
-- The distinction between the last two matters to the caller: one is answered by
-- re-reading the file, the other means something wrote the catalog without
-- moving the revision, and re-reading would not settle it.
--
-- ============================================================
-- What this door owns, and what it does not
-- ============================================================
--
-- The location door owns where a file is. This one owns what it holds - the
-- digest, the size, the modification time, the generation counter - and, at the
-- same path, which physical object is holding it. Those are not the same
-- question: an application that saves by writing a temporary file and swapping
-- it in replaces the object without moving it anywhere, and a file can be
-- renamed without a byte changing.
--
-- It records the fact itself rather than leaving that to the caller, for the
-- reason the location door does: a fact assembled in two steps has a moment when
-- it is on record saying something nobody checked. One authority per kind of
-- fact, and this is the authority for a change of content.
--
-- Derived state is deliberately not touched here. What a fingerprint is, what a
-- thumbnail is for, which similarity group a photo sits in - none of that
-- belongs in the structural door, and teaching it would put every feature's
-- vocabulary inside the one thing they all share. The transaction that calls
-- this clears them, in the same transaction, where that knowledge lives.

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
            content_revision = v_file.content_revision + 1
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

COMMENT ON FUNCTION apply_content_change(BIGINT, BIGINT, TEXT, TEXT, BIGINT, TIMESTAMPTZ, UUID,
        catalog_fact_provenance) IS
    'The only way the catalog changes its mind about what a file contains: compares the state the caller observed from against the row under lock, and either applies the transition once - advancing content_revision and recording the fact - or says which of converged, stale and conflicting it met. Derived state is cleared by the caller in the same transaction, because what is derived is not this door''s vocabulary.';

-- ============================================================
-- Learning a digest is not a change
-- ============================================================
--
-- A file nobody had hashed has no previous content to differ from, so recording
-- its digest for the first time proves nothing happened and invalidates nothing.
-- Separate from the door above because it is a different statement: it advances
-- no revision, writes no fact, and its whole risk is the opposite one - two
-- workers learning different digests for the same file, which is not something
-- to resolve by letting the last one win.

CREATE OR REPLACE FUNCTION learn_content_digest(p_catalog_file_id BIGINT, p_observed_sha256 TEXT,
        p_observed_size_bytes BIGINT, p_observed_modified_at TIMESTAMPTZ)
RETURNS TEXT
LANGUAGE plpgsql
AS $$
DECLARE
    v_file catalog_file%ROWTYPE;
BEGIN
    IF p_catalog_file_id IS NULL OR p_observed_sha256 IS NULL THEN
        RAISE EXCEPTION 'Learning a digest needs the file and the digest' USING ERRCODE = 'NB006';
    END IF;

    SELECT * INTO v_file FROM catalog_file WHERE id = p_catalog_file_id FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'No catalog file %', p_catalog_file_id USING ERRCODE = 'NB001';
    END IF;

    IF v_file.sha256 IS NOT NULL THEN
        -- Somebody got there first. Agreeing is the ordinary case and needs no
        -- write; disagreeing is a content change, and this is not the door for it.
        RETURN CASE WHEN v_file.sha256 = p_observed_sha256 THEN 'ALREADY_KNOWN' ELSE 'CONFLICT' END;
    END IF;

    UPDATE catalog_file
        SET sha256 = p_observed_sha256,
            size_bytes = COALESCE(p_observed_size_bytes, size_bytes),
            modified_at = COALESCE(p_observed_modified_at, modified_at)
        WHERE id = p_catalog_file_id;

    RETURN 'LEARNED';
END;
$$;

COMMENT ON FUNCTION learn_content_digest(BIGINT, TEXT, BIGINT, TIMESTAMPTZ) IS
    'Records a digest for a file that had none. Advances no revision and invalidates nothing: nothing was proved to have happened. A digest that disagrees with one already stored is refused here and belongs to apply_content_change.';