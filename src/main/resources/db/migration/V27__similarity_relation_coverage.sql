-- Which files are already incorporated into the relation universe of one
-- parameter family, so that an arriving photo can be compared against what
-- exists instead of everything being compared against everything again.
--
-- WHY A SECOND TABLE, AND NOT A COLUMN OR A DERIVATION
--
-- similarity_relation stores only approvals. That is deliberate and pays for
-- itself - the rejections outnumber them by more than forty to one - but it
-- costs exactly one thing: a file that was analysed and matched nothing leaves
-- no row at all, and is indistinguishable from a file nobody ever compared.
-- An incremental run has to tell those two apart, and no amount of reading
-- similarity_relation can do it.
--
-- A watermark over catalog_file.id would not do either. Ids are monotonic, but
-- eligibility is not: a photo catalogued years ago becomes eligible the day its
-- fingerprint is finally computed, the day it leaves quarantine, or the day the
-- user stops excluding its folder. Any "everything below this id is done" rule
-- is wrong for exactly those files.
--
-- WHAT A ROW MEANS
--
-- The file is part of the relation universe of (algorithm_id, max_distance,
-- min_similarity): every pair between it and every other covered file has been
-- evaluated, whatever the verdict was.
--
-- It does NOT mean "eligible now". Coverage survives logical deletion,
-- quarantine and the user's exclusions, because none of those change what two
-- images look like - the relations of a hidden file stay true and simply stop
-- being read while it is out. That is what makes a file's return free instead of
-- a recomputation.
--
-- THE RULE THIS ENFORCES ON THE INCREMENTAL RUN
--
-- New files N are compared against the COVERED set C, and against each other -
-- never merely against what is eligible today. Comparing against the eligible
-- set leaves a gap no per-file flag can record: if A is covered but hidden when
-- B arrives, B skips A, both end up covered, and the pair A-B is never evaluated
-- by anybody. SimilarityCoverageModelTest holds that counterexample, and holds
-- the nine scenarios the rule has to survive.
--
-- WHEN A ROW GOES
--
--   * the file is fingerprinted again  -> its relations were computed from an
--     image that no longer exists, so both they and this row are forgotten and
--     the file re-enters as new;
--   * the algorithm, radius or threshold change -> that is a different key, and
--     this table is keyed by all three;
--   * the catalog row is deleted for good -> the cascade below.
--
-- Nothing else removes it. Not exclusion, not quarantine, not a temporary change
-- of eligibility.
--
-- STARTING EMPTY IS CORRECT
--
-- No backfill: an installation upgrading to this version has relations whose
-- covered set cannot be reconstructed, because the files that matched nothing
-- were never written down. The table therefore starts empty, the next full
-- rebuild fills it with everything it analysed, and incremental runs work from
-- there. Guessing a covered set from the relation participants would claim
-- coverage the product cannot honour.
CREATE TABLE similarity_relation_coverage (
	-- The same three values similarity_relation is keyed by, and for the same
	-- reason: they are what decides whether two files relate. The exclusion
	-- signature and the selection policy are deliberately not here - they decide
	-- which files enter an analysis, not what was compared.
	algorithm_id VARCHAR(64) NOT NULL,
	max_distance INTEGER NOT NULL,
	min_similarity INTEGER NOT NULL,

	catalog_file_id BIGINT NOT NULL,

	covered_at TIMESTAMP NOT NULL,

	-- The key is the fact itself, so a run that repeats after a crash inserts
	-- nothing new rather than duplicating: coverage is written with
	-- ON CONFLICT DO NOTHING, in the same transaction as the relations it
	-- accounts for.
	CONSTRAINT pk_similarity_relation_coverage
		PRIMARY KEY (algorithm_id, max_distance, min_similarity, catalog_file_id),

	CONSTRAINT fk_similarity_relation_coverage_file FOREIGN KEY (catalog_file_id)
		REFERENCES catalog_file (id) ON DELETE CASCADE
);

-- Reading is always "which of these eligible files are not yet covered", an
-- anti-join scoped to one family. The primary key already leads with the three
-- parameter columns, so that scoping needs no index of its own; this one covers
-- the other direction, deleting every family's coverage of a single file when it
-- is fingerprinted again.
CREATE INDEX ix_similarity_relation_coverage_file
	ON similarity_relation_coverage (catalog_file_id);