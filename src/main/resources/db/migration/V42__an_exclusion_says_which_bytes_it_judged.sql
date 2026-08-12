-- An exclusion was a decision about a file's content, and it starts saying which.
--
-- "Never offer me this one as a duplicate" is a judgement the user made while
-- looking at a particular photo. The table recorded only which catalogued file
-- it was about, so once that file's bytes were replaced the old judgement went
-- on hiding a picture nobody had ever seen. Silently: the exclusion is a NOT
-- EXISTS inside the duplicate queries, and a row it removes leaves no trace.
--
-- The fix is not to delete the preference when content changes - that throws
-- away something the user said. It is to record what they said it about. An
-- exclusion now carries the content revision it was made against, and applies
-- only while the file is still on that revision. Edit the file and the judgement
-- stops applying, without being forgotten.
--
-- The table is also renamed and rekeyed, and both are corrections rather than
-- taste. duplicate_exclusion_file is the name the exclusion model is heading
-- for, so the rows written from here on are already in the right place. And the
-- foreign key pointed at catalog_file(catalog_file_public_id) - a public
-- identity used as a join key, which is the conflation this whole line of work
-- has been undoing: the public id is what the outside world calls the file, and
-- id is what rows point at.
--
-- The data travels. Every exclusion that exists is carried over against the
-- revision its file is on now, which is the truthful reading: nothing has been
-- observed to change since the judgement was made.

CREATE TABLE duplicate_exclusion_file (
    id BIGSERIAL PRIMARY KEY,

    duplicate_exclusion_file_public_id UUID NOT NULL,

    catalog_file_id BIGINT NOT NULL,

    content_revision BIGINT NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_duplicate_exclusion_file_public_id UNIQUE (duplicate_exclusion_file_public_id),

    -- One judgement per file. Excluding a file twice is the same statement made
    -- twice, and the second one carries the revision that matters.
    CONSTRAINT uk_duplicate_exclusion_file_catalog_file UNIQUE (catalog_file_id),

    CONSTRAINT fk_duplicate_exclusion_file_catalog_file
        FOREIGN KEY (catalog_file_id)
        REFERENCES catalog_file(id)
        ON DELETE CASCADE
);

INSERT INTO duplicate_exclusion_file (duplicate_exclusion_file_public_id, catalog_file_id, content_revision,
        created_at)
    SELECT gen_random_uuid(), m.id, m.content_revision, COALESCE(e.created_at, CURRENT_TIMESTAMP)
      FROM duplicate_file_exclusion e
      JOIN catalog_file m ON m.catalog_file_public_id = e.catalog_file_public_id;

DROP TABLE duplicate_file_exclusion;

CREATE INDEX ix_duplicate_exclusion_file_catalog_file
    ON duplicate_exclusion_file (catalog_file_id, content_revision);

COMMENT ON COLUMN duplicate_exclusion_file.content_revision IS
    'The generation of the file content the user made this judgement about. The exclusion applies only while catalog_file.content_revision still matches: replace the bytes and the judgement stops hiding the new ones, without being forgotten.';