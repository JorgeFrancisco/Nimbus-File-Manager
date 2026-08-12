-- The catalog learns to say which generation of the bytes it is describing.
--
-- Everything derived from a file - its dimensions, its capture date, its
-- perceptual hashes, the group of near-duplicates it belongs to, the thumbnail
-- served for it - was computed from bytes that were true at some moment. Until
-- now nothing recorded which moment. The catalog could tell you when it last
-- analysed a file (last_analysis) and which algorithm did it (analysis_version),
-- and neither of those answers the question that matters when a file is edited
-- in place: is what we computed still about the file that is there?
--
-- content_revision answers it. It is a generation counter for the bytes, and it
-- is deliberately none of its neighbours. It is not version, which is the
-- optimistic lock and moves on every write including a rename. It is not
-- analysis_version, which is about the algorithm and would still be right after
-- an edit. It is not sha256, which is the content itself and is null for
-- everything nobody has hashed yet.
--
-- What makes it useful is what does NOT move it:
--
--   * learning a digest for a file that had none moves nothing. Nobody proved
--     the bytes changed; the catalog merely found out what they are.
--   * a rename or a move moves nothing. The address changed, the file did not.
--   * a second look that agrees with the first moves nothing.
--
-- It advances on one thing only: a digest that disagrees with the digest the
-- catalog held. That is the single event after which everything computed from
-- the old bytes is describing a file that no longer exists at that path.
--
-- And that is the second thing it buys, beyond marking staleness: it is a guard
-- against a late answer. Analysis runs in the background, so a fingerprint job
-- that started before an edit can finish after it and write a result about bytes
-- nobody has any more. Carrying the revision the work began at lets the write
-- refuse instead of quietly resurrecting a previous generation - which a
-- boolean "dirty" flag could never do, because it cannot tell two generations
-- apart.

ALTER TABLE catalog_file ADD COLUMN content_revision BIGINT NOT NULL DEFAULT 1;

COMMENT ON COLUMN catalog_file.content_revision IS
    'Which generation of the file bytes the catalog is describing. Advances only when a digest proves the content differs from the one previously known - never on learning a first digest, never on a rename or move. Derived state computed from an older revision is stale by definition, and a result produced for one is refused against a newer one.';