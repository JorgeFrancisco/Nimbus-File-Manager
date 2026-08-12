-- The placement stops having an identity of its own.
--
-- catalog_file_location carried a generated id as its primary key and a unique
-- catalog_file_id beside it, so every row had two identities for the same
-- thing. Its sisters do not: media_metadata, photo and video are all keyed by
-- catalog_file_id, which is what a component of a file is identified by.
--
-- The spare identity was not idle. The two sequences only coincide in a
-- database that was built in one pass, and a reconcile walked its work with
-- "where l.id > :afterId" while the projection it paged through answered with
-- catalog file ids - so on any catalogue where a placement had ever been
-- deleted and written again, rounds of that walk silently skipped files. The
-- walk was corrected; this removes what made the mistake possible.
--
-- Nothing referenced the column: no foreign key, no query, no projection, no
-- Java reader. What the table keeps is everything that describes the placement,
-- including the two generated columns, which are recomputed by the database
-- from current_path and are therefore untouched by a change of key.

ALTER TABLE catalog_file_location DROP CONSTRAINT uk_catalog_file_location_file;

ALTER TABLE catalog_file_location DROP CONSTRAINT catalog_file_location_pkey;

ALTER TABLE catalog_file_location DROP COLUMN id;

ALTER TABLE catalog_file_location ADD CONSTRAINT catalog_file_location_pkey PRIMARY KEY (catalog_file_id);

COMMENT ON CONSTRAINT catalog_file_location_pkey ON catalog_file_location IS
    'A placement is identified by the file it places. One row per file falls out of the key itself, and the foreign key underneath it keeps the file it names real.';