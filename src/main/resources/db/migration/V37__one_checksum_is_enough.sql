-- MD5 leaves, because nothing was ever asking it anything.
--
-- It was written on every inventoried file, indexed, and read by exactly one
-- thing: a column in the catalog export. No query filtered by it, no duplicate
-- detection grouped by it, no integrity check compared it - the secure move
-- verifies with SHA-256 and always has. An index nobody queries is not free
-- either: it is written on every insert and update of a table this product fills
-- a hundred thousand rows at a time.
--
-- Keeping two digests would be defensible if they answered different questions.
-- They answer the same one, and SHA-256 answers it better: the whole point of a
-- content digest here is that two different files must not collide, and MD5 is
-- the one of the pair for which producing a collision is a solved problem.
--
-- What identifies a file in the export is unaffected: catalog_file_public_id is
-- its identity and sha256 is its content, and those were always two different
-- statements.

DROP INDEX IF EXISTS ix_catalog_file_md5;

ALTER TABLE catalog_file DROP COLUMN md5;

COMMENT ON COLUMN catalog_file.sha256 IS
    'Digest of the file content. Evidence of what the bytes are - never the identity of the row, which is catalog_file_public_id: two different files may legitimately share a digest.';