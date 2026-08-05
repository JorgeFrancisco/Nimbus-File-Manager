-- A rebuild of fingerprints stops being "delete everything and let the absence
-- of a row mean pending".
--
-- media_fingerprint held two jobs at once: it was the published data and it was
-- the ledger of what had already been computed. Because pending meant "there is
-- no row", the only way to re-open the work was to delete the data - and a run
-- interrupted after that delete left the library without the fingerprints of a
-- whole algorithm, for as long as it took the next run to recompute them. Every
-- consumer went on reading that subset as the truth.
--
-- This table takes the second job. It says what a rebuild still owes, so the
-- fingerprints it is replacing can stay published until each one is replaced,
-- one file at a time, in a transaction of its own.
--
-- Additive on purpose: nothing here touches media_fingerprint, its unique key or
-- any query that reads it.
CREATE TABLE fingerprint_rebuild_task (
    -- (kind, algorithm) is the identity of the rebuild, not the execution that
    -- asked for it. An execution dies in ways that have nothing to do with the
    -- work being finished - a lease that lapsed three times ends the row for
    -- good, and a waiting duplicate supersedes it - and the work outlives all of
    -- them. Whichever execution holds the row next adopts what is left here.
    kind VARCHAR(30) NOT NULL,
    algorithm VARCHAR(40) NOT NULL,

    catalog_file_id BIGINT NOT NULL,

    seeded_at TIMESTAMP NOT NULL,

    -- One task per file per target, which is what makes a second request for the
    -- same rebuild idempotent: it tops the list back up instead of starting a
    -- competing one. There is no second work list for a target, by construction.
    CONSTRAINT pk_fingerprint_rebuild_task PRIMARY KEY (kind, algorithm, catalog_file_id),

    -- A file that leaves the catalog owes nothing. This is the whole of the
    -- cleanup: an open list is outstanding work rather than rubbish, and the
    -- startup pass adopts it.
    CONSTRAINT fk_fingerprint_rebuild_task_file
        FOREIGN KEY (catalog_file_id) REFERENCES catalog_file(id) ON DELETE CASCADE
);

-- The drain asks "what does this target still owe" on every batch, and the
-- primary key answers it by prefix. The index below is for the other direction:
-- what a file owes, which is how a per-file replacement consumes its own task.
CREATE INDEX ix_fingerprint_rebuild_task_file ON fingerprint_rebuild_task (catalog_file_id);