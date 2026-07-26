-- A coordinate with no administrative boundary within reach is open water: a
-- fact of its own, kept apart from the place names so it never masquerades as a
-- country. The label the screens show comes from i18n, never from this column.
ALTER TABLE media_geo_location ADD COLUMN open_sea BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE geo_resolution_cache ADD COLUMN open_sea BOOLEAN NOT NULL DEFAULT FALSE;