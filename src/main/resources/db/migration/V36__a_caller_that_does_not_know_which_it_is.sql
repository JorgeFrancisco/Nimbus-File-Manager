-- A door for the caller that cannot say whether it is renaming or moving.
--
-- rename_catalog_file and move_catalog_file exist to refuse a change that is not
-- the one they name, which is right for a feature that knows: the Files screen
-- renaming a file states a rename, and a bug that turned it into a move should
-- fail loudly rather than write the wrong kind of fact.
--
-- Organization is the other kind of caller. It computes a destination folder
-- from a date and keeps the file's own name, so which of the two it turns out to
-- be is an outcome rather than an intention - and a plan whose target lands back
-- in the same folder, because the name had to be sanitised, would be refused by
-- both of the assertive doors for being the other one.
--
-- So this classifies instead of asserting, using the same rule the wrappers
-- enforce. It is not a third way to write a location: it decides an event type
-- and hands the work to the one function that does the writing.

CREATE OR REPLACE FUNCTION relocate_catalog_file(p_catalog_file_id BIGINT, p_catalog_file_event_public_id UUID,
        p_expected_old_path TEXT, p_new_path TEXT, p_path_flavor TEXT, p_occurred_at TIMESTAMPTZ, p_source TEXT)
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
        p_new_path, p_path_flavor, p_occurred_at, p_source);
END;
$$;

COMMENT ON FUNCTION relocate_catalog_file(BIGINT, UUID, TEXT, TEXT, TEXT, TIMESTAMPTZ, TEXT) IS
    'A file goes somewhere, and the fact is classified rather than asserted. For callers that compute a destination instead of naming an intention.';