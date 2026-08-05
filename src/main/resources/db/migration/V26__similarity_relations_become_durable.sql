-- Approved similarity relations, kept so that a small change to the library costs
-- proportionally to the change instead of to the library.
--
-- The grouping is greedy and order-dependent, and that makes the two directions
-- of change behave differently. Adding a photo is equivalent to a full rebuild,
-- because the photo's id is higher than every existing one and the greedy loop
-- never revisits a decision it already took. Removing one is NOT equivalent: a
-- member can be the reason another candidate was refused, and dropping it would
-- have let that candidate in. Measured on a real library the rebuild is 37 s,
-- while re-running only the grouping over the surviving relations is 50 ms - and
-- the relations do not change when a file goes away.
--
-- Only approvals are stored. The pairs that failed outnumber them by more than
-- forty to one (1.333.176 candidates against 31.747 approvals at SSIM 95), and
-- the grouping cannot tell a rejection from an absence: outside the radius,
-- scored below the threshold and never compared are one answer to it. Rejections
-- would be forty megabytes of rows nobody reads.
--
-- WHAT THIS IS KEYED BY, AND WHY NOT parameters_digest
--
-- A relation is a fact about two files under three parameters: which fingerprint
-- algorithm produced the hashes, how far apart they were allowed to be, and what
-- SSIM they had to reach. Those three, and nothing else, decide whether A relates
-- to B.
--
-- similarity_grouping.parameters_digest carries more than that - the exclusion
-- signature and the selection policy - because those decide which files enter an
-- analysis. They do not decide whether two files are similar. Keying relations by
-- the digest would throw away every relation whenever a folder was excluded,
-- recomputing facts that had not changed. So the four concepts stay separate:
--
--   * the parameter family                 -> similarity_grouping.parameters_digest
--   * which files are currently eligible   -> the exclusion tables, evaluated per run
--   * the published version of the groups  -> similarity_grouping + its groups
--   * the approved relations               -> this table
--
-- The consequence is that relations survive re-publication: a new BUILDING
-- version does not copy 31.747 rows to add one photo's handful.
CREATE TABLE similarity_relation (
	id BIGSERIAL PRIMARY KEY,

	-- The three parameters that decide the relation, stored rather than hashed so
	-- a reader can see which comparison produced the row.
	algorithm_id VARCHAR(64) NOT NULL,
	max_distance INTEGER NOT NULL,
	min_similarity INTEGER NOT NULL,

	-- Ordered, not merely a pair: A-B and B-A are the same relation, and the check
	-- below makes the database refuse the other spelling instead of trusting every
	-- writer to canonicalise. It also makes A-A impossible, since a file cannot be
	-- strictly less than itself.
	--
	-- The catalog id and not the public id, for one reason that matters: the
	-- grouping visits candidates in catalog_file.id order, so this column IS the
	-- ordering the greedy algorithm depends on. Canonicalising by it means the
	-- stored pair already reads in analysis order, the eligibility query returns the
	-- same type, and the row is half the size of a UUID pair.
	first_catalog_file_id BIGINT NOT NULL,
	second_catalog_file_id BIGINT NOT NULL,

	-- The SSIM the pair scored. Persisted because the value is already paid for and
	-- is read again: the group's floor comes from the worst pair in it, and the
	-- screen shows that percentage. Recomputing it would mean loading two 32x32
	-- samples to learn something already known.
	similarity_percent INTEGER NOT NULL,

	computed_at TIMESTAMP NOT NULL,

	CONSTRAINT ck_similarity_relation_canonical CHECK (first_catalog_file_id < second_catalog_file_id),
	CONSTRAINT ck_similarity_relation_percent CHECK (similarity_percent BETWEEN 0 AND 100),

	CONSTRAINT uk_similarity_relation UNIQUE (algorithm_id, max_distance, min_similarity, first_catalog_file_id,
		second_catalog_file_id),

	-- A file deleted for good takes its relations with it: they are facts about a
	-- file that no longer exists. An *excluded* file is untouched here, because
	-- exclusion hides a file from an analysis without deleting it, and un-excluding
	-- it must not require recomputing what was already true.
	CONSTRAINT fk_similarity_relation_first FOREIGN KEY (first_catalog_file_id)
		REFERENCES catalog_file (id) ON DELETE CASCADE,

	CONSTRAINT fk_similarity_relation_second FOREIGN KEY (second_catalog_file_id)
		REFERENCES catalog_file (id) ON DELETE CASCADE
);

-- Reading is always "every relation of this parameter set", to feed the grouping.
CREATE INDEX ix_similarity_relation_parameters
	ON similarity_relation (algorithm_id, max_distance, min_similarity);

-- And sometimes "every relation this file takes part in", for an incremental
-- update. The unique constraint already serves lookups that start at the first
-- id; this one covers the other side, so neither direction is a scan.
CREATE INDEX ix_similarity_relation_second
	ON similarity_relation (second_catalog_file_id);