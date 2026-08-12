-- The name of a file stops being a column and becomes a question about its path.
--
-- catalog_file.file_name was a copy of the last segment of the path, kept in a
-- different table from the path itself, and every rename had to remember to move
-- both. V30 removed it. What is left is a handful of screens that legitimately
-- need the name - to show it, and in three cases to order by it while the
-- database is paginating, which Java cannot do afterwards.
--
-- So the database answers it, next to the two derivations it already owns.
-- Deriving it in Java instead would put the rule in two places the moment one
-- query had to sort by it, and the two would disagree on the day a path came in
-- with the other separator.
--
-- Not a stored column, and that is a deliberate difference from path_key and
-- current_folder. Those are matched and grouped against - they earn an index.
-- This one is read and sorted within a set some other predicate already narrowed,
-- so it costs a substring per row of an answer rather than a scan. If a sort ever
-- shows up as the cost of a screen, promoting it to GENERATED ALWAYS ... STORED
-- is an additive change that needs no query rewritten.

CREATE OR REPLACE FUNCTION catalog_file_name(path TEXT, flavor TEXT)
RETURNS TEXT
LANGUAGE plpgsql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $$
DECLARE
    reversed TEXT := reverse(path);
    cut INT;
    slash INT;
BEGIN
    -- Found from the right, and by position rather than by pattern: a regular
    -- expression here would need a character class holding a backslash, which is
    -- the one construct in this file that could be silently wrong in a way no test
    -- of POSIX paths would ever catch.
    IF flavor = 'WINDOWS' THEN
        cut := strpos(reversed, chr(92));
        slash := strpos(reversed, '/');

        IF cut = 0 THEN
            cut := slash;
        ELSIF slash > 0 AND slash < cut THEN
            cut := slash;
        END IF;
    ELSIF flavor = 'POSIX' THEN
        cut := strpos(reversed, '/');
    ELSE
        RAISE EXCEPTION 'Unknown path flavor: %', flavor;
    END IF;

    -- No separator at all means the whole thing is a name. A path ending in one
    -- yields the empty string, which is honest: nothing is named there.
    IF cut = 0 THEN
        RETURN path;
    END IF;

    RETURN substr(path, length(path) - cut + 2);
END;
$$;

COMMENT ON FUNCTION catalog_file_name(TEXT, TEXT) IS
    'The last segment of a path, spelled the way the path is. Purely textual, like canonicalize_catalog_path and parent_catalog_path.';