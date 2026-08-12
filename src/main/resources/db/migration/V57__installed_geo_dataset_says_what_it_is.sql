-- ============================================================
-- geo_dataset_state: the installed dataset says what it is, next to its rows
--
-- The identity of the installed geographic dataset - which version it is, where
-- it came from and when it was imported - lived in workspace/geodata/metadata.json,
-- a file written after the transaction that wrote the boundaries. Two media with
-- no transaction between them cannot agree: a run that imported the rows and died
-- before writing the file left a database holding one dataset and a file
-- describing the previous one, and no authority could say when the installed rows
-- had actually arrived. Reconstructing it was impossible rather than laborious,
-- which is why the fact moves here instead of being repaired.
--
-- Written by the same transaction that writes the boundaries, so version and
-- imported_at either both describe the rows that are there or neither exists.
-- What is derivable is deliberately absent: the record count is COUNT(*) on
-- geo_admin_boundary, the size on disk is the geodata folder, and the last error
-- belongs to the execution that failed. Nothing here is a second copy of a fact
-- that already has an owner.
--
-- complete is the commit point of an installation. The import of the three
-- levels, the territory completion that follows it and the publication of the
-- downloaded files are separate transactions by design - a failing territory
-- must not roll back a worldwide import - so "the rows are there" never meant
-- "the installation finished". It does now: complete turns true in its own
-- statement after every other step succeeded, and any crash before that leaves a
-- dataset that the next run rebuilds instead of trusting.
-- ============================================================

CREATE TABLE geo_dataset_state (
    id SMALLINT PRIMARY KEY,

    dataset_version VARCHAR(50) NOT NULL,
    source VARCHAR(40) NOT NULL,
    provider VARCHAR(120) NOT NULL,
    license VARCHAR(200) NOT NULL,

    imported_at TIMESTAMP NOT NULL,

    complete BOOLEAN NOT NULL DEFAULT FALSE,

    -- One installation exists at a time: the table describes the dataset the
    -- application resolves against, and there is only ever one of those.
    CONSTRAINT ck_geo_dataset_state_singleton CHECK (id = 1)
);

-- ------------------------------------------------------------
-- The geographic dataset is discarded rather than carried over.
--
-- Migrations here transport the data they reshape, because the catalog, the
-- perceptual hashes and the resolved locations are the user's and cannot be
-- produced again. This dataset is the opposite kind of artifact: the application
-- downloads it from its source on demand, exactly as it does on a fresh
-- installation, and rebuilding it costs one import. There is no imported_at to
-- carry over for the rows below - that is the fact this migration exists because
-- nothing could reconstruct - so inventing one to preserve them would write down
-- a date nobody observed.
--
-- The resolution cache goes with them: every row in it is an answer computed
-- against boundaries that are being removed, and an answer derived from data that
-- no longer exists is not worth keeping.
-- ------------------------------------------------------------

DELETE FROM geo_resolution_cache;

DELETE FROM geo_admin_boundary;