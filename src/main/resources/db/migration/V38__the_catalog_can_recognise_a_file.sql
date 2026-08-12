-- The catalog learns to recognise a file the way the operating system does.
--
-- Until now the only thing tying a row to a file on disk was its path, so a
-- rename outside the application looked exactly like one file disappearing and
-- an unrelated one appearing. Recovering from that meant reading and hashing
-- every candidate on disk to find a pair with identical content - paying for an
-- answer the operating system had already given and nobody had kept.
--
-- What it kept now is the file id Windows issues: unique within a volume, and
-- preserved across rename and move inside it. Three columns rather than one,
-- because none of the three is usable alone. The kind says what the value means,
-- since platforms do not agree on what "the same object" is. The scope says
-- where it is unique - the volume - because the same number names a different
-- file on the next drive. Only the value is the number, and it is stored as text
-- because it does not fit a signed BIGINT: a Windows file id is an unsigned
-- 64-bit whose top bits carry a sequence number, and the high values occur.
--
-- Deliberately nullable, and mostly null to begin with. Filling it for a library
-- already catalogued would mean opening 150k files to ask each one its id, and
-- that cost has not been measured against its benefit. It is filled where it
-- arrives on its own: from the journal replay at startup, and from the live
-- watch, both of which are handed it by the operating system at no extra cost.
--
-- No unique constraint, on purpose. Two rows may legitimately carry one identity
-- - NTFS hard links are one file with two names - so uniqueness would forbid
-- something real. What might look like the other collision cannot happen: when a
-- deleted file's MFT record is handed to the next file created, the sequence
-- number in the id increments, so a stale identity is never issued again. It
-- becomes inert, not wrong.

ALTER TABLE catalog_file_location
    ADD COLUMN filesystem_identity_kind VARCHAR(32),
    ADD COLUMN filesystem_identity_scope TEXT,
    ADD COLUMN filesystem_identity_value TEXT;

-- An identity is the three together. Two of them is not a partial identity, it
-- is a row that claims to know something it cannot answer.
ALTER TABLE catalog_file_location
    ADD CONSTRAINT ck_catalog_file_location_filesystem_identity
    CHECK (num_nonnulls(filesystem_identity_kind, filesystem_identity_scope, filesystem_identity_value) IN (0, 3));

-- Partial: the question asked of this index is always "which row is this object",
-- and a row without an identity can never be the answer. While the columns fill
-- opportunistically that excludes nearly every row.
CREATE INDEX ix_catalog_file_location_filesystem_identity
    ON catalog_file_location (filesystem_identity_scope, filesystem_identity_value)
    WHERE filesystem_identity_value IS NOT NULL;

COMMENT ON COLUMN catalog_file_location.filesystem_identity_value IS
    'What the operating system calls this object, unique within filesystem_identity_scope. Evidence of physical continuity across a rename or a move - never the identity of the catalogued file, which is catalog_file_public_id and survives things this does not, such as a copy-and-delete or a move to another volume.';