-- The watcher and the catalog stop having two answers for what a path is called,
-- and an announcement starts saying what it was for.
--
-- ============================================================
-- One spelling of a path
-- ============================================================
--
-- A path this product announced it was writing was keyed by lowercasing it.
-- That is right on Windows and wrong everywhere else: on POSIX two names
-- differing only in case are two files, so announcing one silenced real changes
-- to the other. It was also a second answer to a question already settled -
-- canonicalize_catalog_path is what every location row is keyed by - and two
-- authorities on the spelling of a path is the kind of disagreement that
-- surfaces much later as a change nobody was ever told about.
--
-- So the row keeps the path as it was announced, records which rules it is read
-- under, and lets the key be derived by the same function the catalog uses. On
-- Windows that is strictly more than the old key managed: the separator is
-- folded too, so D:\Fotos\a.jpg and d:/fotos/a.jpg now meet, which they did not
-- before.
--
-- ============================================================
-- What the announcement was for
-- ============================================================
--
-- An entry used to name a path and stop there, which reads as "ignore anything
-- that happens here for the next five minutes". That is more than this product
-- ever knows. Moving a file out of A tells us A will be emptied; it says nothing
-- about a file the user drops into A a minute later, and swallowing that one is
-- exactly the kind of silence this table must not produce - a path being freed
-- is a path likely to be reused.
--
-- So each entry records which half of an effect it explains. Vacating never
-- accounts for a file appearing; occupying never accounts for one vanishing.
-- Both still account for a modification, because both produce them - clearing a
-- read-only attribute to delete a file is a change to that file, and carrying a
-- timestamp onto a moved one is a change to that.
--
-- A path can hold both at once - the destination of one move is the source of
-- the next - so the two are separate rows and the key is the pair.
--
-- ============================================================
-- Why the rows are not carried
-- ============================================================
--
-- This table says "the application is writing these paths at this moment".
-- Migrations run at startup, before a watcher is armed and before a worker has
-- claimed anything, so every row standing here describes a write that ended when
-- the process that announced it did.
--
-- Carrying them is not merely pointless, it is unsafe. The old key is
-- lowercased and the spelling it came from is not recoverable, so a POSIX
-- installation would be given paths that never existed - and each one is a live
-- announcement, which is a window in which a real change goes unreported.
-- Dropping them costs at most one redundant inventory; keeping them costs a
-- change nobody sees. This is transient coordination state, not the years of
-- catalog a migration is otherwise obliged to carry across.

DELETE FROM self_written_path;

ALTER TABLE self_written_path DROP CONSTRAINT self_written_path_pkey;

ALTER TABLE self_written_path DROP COLUMN path_key;

ALTER TABLE self_written_path ADD COLUMN announced_path TEXT NOT NULL;

ALTER TABLE self_written_path ADD COLUMN path_flavor VARCHAR(16) NOT NULL;

ALTER TABLE self_written_path ADD COLUMN role VARCHAR(16) NOT NULL;

-- Generated rather than supplied, for the reason the location row is: two
-- columns holding a path and its key can disagree, and nothing would notice.
ALTER TABLE self_written_path
    ADD COLUMN path_key TEXT GENERATED ALWAYS AS (canonicalize_catalog_path(announced_path, path_flavor)) STORED;

ALTER TABLE self_written_path ADD CONSTRAINT pk_self_written_path PRIMARY KEY (path_key, role);

ALTER TABLE self_written_path ADD CONSTRAINT ck_self_written_path_role
    CHECK (role IN ('VACATING', 'OCCUPYING'));

COMMENT ON TABLE self_written_path IS
    'Paths this product is writing right now, so its own effects do not reach it again as foreign changes. Read, never consumed on first match: one write raises several notifications and a long one spreads them across polls. An entry ends when the operation that made it settles or revokes it, and by ceiling if neither ever does.';

COMMENT ON COLUMN self_written_path.announced_path IS
    'The path as the operation announced it, kept in its own spelling so the key can be derived rather than stored beside it.';

COMMENT ON COLUMN self_written_path.path_flavor IS
    'Which rules decide whether two spellings name the same place. The same flavor a location row carries, and read by the same function.';

COMMENT ON COLUMN self_written_path.role IS
    'Which half of an effect this entry explains: VACATING for a path this product is emptying, OCCUPYING for one it is filling. What it does not explain is what keeps a reused path visible - a file appearing where one was moved away from is nothing this product announced.';

COMMENT ON COLUMN self_written_path.path_key IS
    'Canonical spelling under the flavor, and half of what is ever compared. Derived by canonicalize_catalog_path, which is what the catalog keys locations by - so the watcher and the catalog cannot disagree about whether two paths are one.';