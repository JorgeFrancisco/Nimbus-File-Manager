-- The catalogue stops pretending it can hold a location somebody typed in.
--
-- media_geo_location has carried a manual flag since the first schema, and the
-- model treated it as a decision to protect: a manual location was never
-- overwritten by resolution, always qualified for organization folders whatever
-- its confidence, and was excluded from every rebuild. What it never had was a
-- producer. No screen, endpoint, command or import ever set it - the only write
-- in the whole application sets it to false - so the flag is false on every row
-- of every catalogue, and the protection guards nothing.
--
-- It was worse than idle. media_geo_location descends from media_metadata, which
-- the content door deletes whenever a file's bytes change, so a manual location
-- would have been destroyed by cascade on the first edit of the file it
-- described - by the one path that never consulted the flag. The model promised
-- a guarantee the schema did not keep.
--
-- So the concept goes, rather than being repaired for a feature that has no
-- requirement yet. When editing a location by hand is actually built, what owns
-- it - the catalogued item or the extracted content - is the first question to
-- answer, and the schema that answers it is not this one.
--
-- The provider is mapped before the column is dropped. Nothing ever wrote
-- MANUAL there either, but the value is part of a string enum the application
-- reads back, and a row carrying it in some catalogue nobody has seen would
-- otherwise fail to load. The place it names is kept; only the claim about who
-- resolved it becomes unknown.

UPDATE media_geo_location SET provider = 'UNKNOWN' WHERE provider = 'MANUAL';

ALTER TABLE media_geo_location DROP COLUMN manual;