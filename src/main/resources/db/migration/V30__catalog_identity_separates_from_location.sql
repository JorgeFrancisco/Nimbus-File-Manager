-- The catalog stops using a path as the identity of a file.
--
-- catalog_file.file_key was the absolute path, UNIQUE, rewritten by every move,
-- and duplicated in catalog_file_location.current_path. Nothing ever pointed a
-- foreign key at it, so it was never a relational identity - but it *was* the
-- function the inventory used to answer "is this the same file?", and that is
-- where two defects came from: a file moved outside Nimbus became a second
-- CatalogFile (losing its fingerprints and exclusions), and a different file
-- appearing at an old path inherited the previous identity.
--
-- The three tables now carry one responsibility each:
--
--   catalog_file           identity, content attributes and lifecycle
--   catalog_file_location  the last known location, and nothing historical
--   catalog_file_event     the immutable facts observed during the file's life
--
-- Location is deliberately NOT unique. A MISSING file keeps the last place it
-- was seen, and a different file may legitimately occupy that same path in the
-- meantime - the two rows coexist. The real rule ("only one ACTIVE file occupies
-- a library path") spans two tables and cannot be a UNIQUE index; it will be
-- held by the transactional functions that become the single write door.
--
-- Timestamps that mean an instant become TIMESTAMPTZ. The application clock
-- takes its zone from a user-editable setting, and every column was a naked
-- TIMESTAMP: changing that setting silently reinterpreted the whole history.
--
-- This migration deliberately does not carry data across, which the project's
-- rule for shape-changing migrations otherwise requires. The decision is
-- explicit and belongs to this front: the model is a breaking change, the
-- database is recreated, and there is no installed base to preserve yet.

-- ============================================================
-- Canonical path form, decided by data rather than by the host
-- ============================================================

-- Lexical only: it never touches the filesystem, resolves no symlink and does
-- not care whether the file exists. The flavor travels as an argument - and is
-- stored on the row - so a catalog created on Windows keeps being read with
-- Windows rules even when the server runs somewhere else, which is what lets
-- this be IMMUTABLE and therefore usable by a generated column.
--
-- It canonicalizes how a path is *spelled* - separator, case, trailing slash -
-- and not how it is *structured*: `.`, `..` and repeated separators are left
-- exactly as given. Resolving those is the caller's, and every path reaching
-- this database has already been through Path.normalize(). Writing it a second
-- time here would be the same rule in two languages, and the one place it could
-- disagree with itself.
CREATE OR REPLACE FUNCTION canonicalize_catalog_path(path TEXT, flavor TEXT)
RETURNS TEXT
LANGUAGE plpgsql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $$
DECLARE
    unified TEXT;
BEGIN
    IF flavor = 'WINDOWS' THEN
        -- Both separators name the same place. Case is folded by lower(), which
        -- reads the database collation and does fold the accented names a photo
        -- library is full of - but it is not, and does not claim to be, the same
        -- table Windows itself uses. This is Nimbus's own stable form, close
        -- enough that two spellings of one name meet, and the only form that ever
        -- decides anything is this one.
        unified := lower(replace(path, chr(92), '/'));
    ELSIF flavor = 'POSIX' THEN
        -- Case is significant here: two names differing only in case are two files.
        unified := path;
    ELSE
        -- No CHECK constraint enumerates the flavors on the table: this is where an
        -- unknown one is refused, and the generated column makes the refusal reach
        -- the INSERT.
        RAISE EXCEPTION 'Unknown path flavor: %', flavor;
    END IF;

    -- A trailing separator names the same folder, so it is dropped - except when
    -- it is the whole path, which is the filesystem root and means something.
    RETURN regexp_replace(unified, '(.)/+$', '\1');
END;
$$;

COMMENT ON FUNCTION canonicalize_catalog_path(TEXT, TEXT) IS
    'Canonical spelling of a path for comparison and lookup, under the rules of the given flavor. Folds separator, case and trailing separator; leaves "." and ".." as given, which the caller has already resolved. Performs no filesystem access and resolves no link.';

-- The folder a path sits in, in the same spelling the path itself uses. It
-- exists so current_folder can be derived rather than supplied: stored side by
-- side, the two could disagree, and a row claiming a file at D:/Fotos/a.jpg
-- lives in E:/Outro would be nobody's fault and nobody's to notice.
CREATE OR REPLACE FUNCTION parent_catalog_path(path TEXT, flavor TEXT)
RETURNS TEXT
LANGUAGE plpgsql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $$
DECLARE
    -- The same two characters, spelled twice: a bracket expression treats a
    -- backslash as an escape, so the pattern needs it doubled, while rtrim wants
    -- the characters themselves.
    escaped_separators TEXT;
    raw_separators TEXT;
    parent TEXT;
BEGIN
    IF flavor = 'WINDOWS' THEN
        -- Either separator ends a segment here. On POSIX a backslash is an
        -- ordinary character in a file name and must never cut a path in two.
        escaped_separators := chr(92) || chr(92) || '/';
        raw_separators := chr(92) || '/';
    ELSIF flavor = 'POSIX' THEN
        escaped_separators := '/';
        raw_separators := '/';
    ELSE
        RAISE EXCEPTION 'Unknown path flavor: %', flavor;
    END IF;

    parent := regexp_replace(path, '[^' || escaped_separators || ']+$', '');

    -- A folder is not spelled with a trailing separator - unless the separator is
    -- the whole of it, which is a root and names something.
    IF length(parent) > 1 THEN
        parent := rtrim(parent, raw_separators);

        IF parent = '' THEN
            parent := substr(path, 1, 1);
        END IF;
    END IF;

    RETURN parent;
END;
$$;

COMMENT ON FUNCTION parent_catalog_path(TEXT, TEXT) IS
    'The containing folder of a path, spelled the way the path is. Purely textual, like canonicalize_catalog_path.';

-- ============================================================
-- catalog_file: identity, content and lifecycle - no location
-- ============================================================

ALTER TABLE catalog_file DROP COLUMN file_key;
ALTER TABLE catalog_file DROP COLUMN file_name;

-- The USING clauses name UTC rather than letting the session zone decide, so the
-- statement means the same thing wherever it runs. On the empty database this
-- front recreates they convert nothing; they are here because the conversion has
-- to be spelled deterministically, not because old rows are being carried across.
ALTER TABLE catalog_file
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN modified_at TYPE TIMESTAMPTZ USING modified_at AT TIME ZONE 'UTC',
    ALTER COLUMN imported_at TYPE TIMESTAMPTZ USING imported_at AT TIME ZONE 'UTC',
    ALTER COLUMN lifecycle_changed_at TYPE TIMESTAMPTZ USING lifecycle_changed_at AT TIME ZONE 'UTC',
    ALTER COLUMN last_analysis TYPE TIMESTAMPTZ USING last_analysis AT TIME ZONE 'UTC';

COMMENT ON TABLE catalog_file IS
    'A file the catalog knows, by an identity that survives every move and rename. Carries what the file is and what state it is in; where it is lives in catalog_file_location.';

COMMENT ON COLUMN catalog_file.public_id IS
    'Stable external identity, generated by the application. Never derived from the path.';
COMMENT ON COLUMN catalog_file.created_at IS
    'Creation instant reported by the filesystem for the file itself.';
COMMENT ON COLUMN catalog_file.modified_at IS
    'Last-modification instant reported by the filesystem for the file itself.';
COMMENT ON COLUMN catalog_file.imported_at IS
    'Instant the catalog first knew this file, which is about the catalog and not about the file.';
COMMENT ON COLUMN catalog_file.lifecycle_status IS
    'Whether the file is present, absent from where it was expected, or removed. Says nothing about where it is.';
COMMENT ON COLUMN catalog_file.lifecycle_changed_at IS
    'Instant the lifecycle last actually changed, which the retention clock reads. Re-confirming the same state does not move it.';

-- ============================================================
-- catalog_file_location: the last place this file was seen
-- ============================================================

-- Dropped rather than altered: the primary key moves off catalog_file_id, three
-- columns leave and two arrive. Nothing in the schema points a foreign key at
-- this table, so recreating it costs nothing beyond the rows themselves.
DROP TABLE catalog_file_location;

CREATE TABLE catalog_file_location (
    id BIGSERIAL PRIMARY KEY,

    catalog_file_id BIGINT NOT NULL,

    current_path TEXT NOT NULL,
    path_flavor VARCHAR(16) NOT NULL,
    path_key TEXT GENERATED ALWAYS AS (canonicalize_catalog_path(current_path, path_flavor)) STORED,

    current_folder TEXT GENERATED ALWAYS AS (parent_catalog_path(current_path, path_flavor)) STORED,

    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_catalog_file_location_file UNIQUE (catalog_file_id),

    CONSTRAINT fk_catalog_file_location_file
        FOREIGN KEY (catalog_file_id)
        REFERENCES catalog_file(id)
        ON DELETE CASCADE
);

-- Not unique, and that is the point: a file that went MISSING keeps the last
-- path it was seen at while another file legitimately occupies that path.
CREATE INDEX ix_catalog_file_location_path_key ON catalog_file_location(path_key);
CREATE INDEX ix_catalog_file_location_current_folder ON catalog_file_location(current_folder);

COMMENT ON TABLE catalog_file_location IS
    'The last known location of a file. Not a history - what moved and when lives in catalog_file_event - and not a claim that the file is there now, which is what the lifecycle says.';

COMMENT ON COLUMN catalog_file_location.current_path IS
    'The path as the filesystem spells it, kept for use and display rather than for comparison.';
COMMENT ON COLUMN catalog_file_location.path_flavor IS
    'Which rules canonicalize this row, so a catalog created on one platform keeps being read under the rules it was written with.';
COMMENT ON COLUMN catalog_file_location.path_key IS
    'Canonical form of current_path, derived by the database alone and never supplied by a caller. For comparison and lookup only; it is neither an identity nor an authority on where the file is.';
COMMENT ON COLUMN catalog_file_location.current_folder IS
    'The containing folder, derived by the database from current_path so it can never contradict it. Materialized because grouping and listing by folder read it directly.';
COMMENT ON COLUMN catalog_file_location.updated_at IS
    'Instant this projection was last written.';

-- ============================================================
-- catalog_file_event: what happened, as it happened
-- ============================================================

CREATE TABLE catalog_file_event (
    id BIGSERIAL PRIMARY KEY,

    catalog_file_event_public_id UUID NOT NULL,

    catalog_file_id BIGINT NOT NULL,

    event_type VARCHAR(64) NOT NULL,

    old_path TEXT,
    new_path TEXT,

    inventory_root_path TEXT,

    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    source VARCHAR(64) NOT NULL,

    filesystem_identity_kind VARCHAR(64),
    filesystem_identity_scope VARCHAR(255),
    filesystem_identity_value VARCHAR(255),

    CONSTRAINT uk_catalog_file_event_public_id UNIQUE (catalog_file_event_public_id),

    -- Kind and value are the smallest piece of evidence that means anything:
    -- an identity nobody can interpret, or a kind identifying nothing, are both
    -- half a fact. Scope stays optional because not every kind is scoped - but it
    -- cannot arrive alone, since a volume qualifies an identity that is not there.
    CONSTRAINT ck_catalog_file_event_identity_pairing
        CHECK ((filesystem_identity_kind IS NULL AND filesystem_identity_value IS NULL
                AND filesystem_identity_scope IS NULL)
            OR (filesystem_identity_kind IS NOT NULL AND filesystem_identity_value IS NOT NULL)),

    CONSTRAINT fk_catalog_file_event_file
        FOREIGN KEY (catalog_file_id)
        REFERENCES catalog_file(id)
        ON DELETE CASCADE
);

-- The timeline of one file. id breaks ties so two facts sharing an instant still
-- read back in the order they were recorded.
CREATE INDEX ix_catalog_file_event_file_time
    ON catalog_file_event(catalog_file_id, occurred_at, id);

COMMENT ON TABLE catalog_file_event IS
    'Facts observed or produced during the life of a file. Meant to be written once and never updated - a discipline the write path keeps, not something this schema enforces yet; the current state is projected elsewhere.';

COMMENT ON COLUMN catalog_file_event.catalog_file_event_public_id IS
    'Stable identity of the fact, generated by the application and used as the idempotency key when an operation is retried.';
COMMENT ON COLUMN catalog_file_event.event_type IS
    'What happened, in the vocabulary of the application. Deliberately not constrained here, so a new kind of fact needs no migration.';
COMMENT ON COLUMN catalog_file_event.old_path IS
    'Where the file was before the fact, when the fact had a before.';
COMMENT ON COLUMN catalog_file_event.new_path IS
    'Where the file was after the fact, when the fact had an after.';
COMMENT ON COLUMN catalog_file_event.inventory_root_path IS
    'The configured root under which the fact was observed. Context of origin, not where the file is now, and absent when no root was involved.';
COMMENT ON COLUMN catalog_file_event.occurred_at IS
    'When the fact happened, according to whoever observed it.';
COMMENT ON COLUMN catalog_file_event.recorded_at IS
    'When the database stored it, which may be much later than it happened.';
COMMENT ON COLUMN catalog_file_event.source IS
    'What observed or produced the fact. Not constrained here, for the same reason as event_type.';
COMMENT ON COLUMN catalog_file_event.filesystem_identity_kind IS
    'Which kind of identity the filesystem offered, when it offered one.';
COMMENT ON COLUMN catalog_file_event.filesystem_identity_scope IS
    'Where that identity is valid - a volume or device - since such identities are rarely unique beyond one.';
COMMENT ON COLUMN catalog_file_event.filesystem_identity_value IS
    'The identity itself, as evidence for recognising this file again. Never a relational identity, and never required.';