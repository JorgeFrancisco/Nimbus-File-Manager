-- The parameters that decide a relation, for media whose comparison takes more
-- than three of them.
--
-- WHY A DIGEST AND NOT MORE COLUMNS
--
-- A relation is a fact about two files under the parameters that produced it,
-- and the three columns already here - algorithm, radius, threshold - are the
-- whole list for photos. Videos have five more, and every one of them can change
-- the verdict or the score: the concordant-frame quorum and the trimmed-mean
-- count decide whether an approval survives, the duration and aspect tolerances
-- decide whether the pair is compared at all, and the frame count decides which
-- frames align. Storing a relation without them would serve an answer computed
-- under settings nobody is using any more.
--
-- Five video columns in a shared table would be five nulls on every photo row,
-- a wider key for both, and the same conversation again for the next medium. So
-- what is added is one column that holds the digest of whatever else the medium's
-- comparison depends on:
--
--   * photo -> '' , because the three columns already say everything;
--   * video -> the digest of quorum, trim, duration tolerance and aspect
--     tolerance, computed from the *effective* values (after clamping), so a
--     configuration out of range produces the digest of the bound it was clamped
--     to.
--
-- framesPerFingerprint is deliberately NOT in the digest. It is part of the
-- algorithm's identity - "..._FRAMES_V1" implies five - and changing it changes
-- the stored fingerprint's sampleIndex layout, which already requires a new
-- algorithm_id. Putting it in both places would let the two disagree.
--
-- WHY NOT algorithm_id WITH A SUFFIX
--
-- Because that column means "which algorithm produced the hashes", and it is
-- read as such: forgetAlgorithm() deletes by equality on it when a fingerprint
-- rebuild throws the hashes away, and it is the same string
-- media_fingerprint.algorithm holds. A suffixed value would break the first and
-- silently stop corresponding to the second.
--
-- EXISTING ROWS
--
-- The default carries them: every photo relation and every photo coverage row
-- already stored belongs to a comparison with no extra parameters, which is
-- exactly what '' means, so they keep their identity and are read by the same
-- queries afterwards. Nothing is recomputed and nothing is deleted - the point
-- of keying a relation narrowly is that facts survive changes that did not
-- touch them.
ALTER TABLE similarity_relation
	ADD COLUMN relation_digest VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE similarity_relation_coverage
	ADD COLUMN relation_digest VARCHAR(64) NOT NULL DEFAULT '';

-- The key gains the column, because two configurations of the same medium are
-- two different sets of facts about the same pairs. Without it a video analysed
-- at a quorum of 3 and one analysed at a quorum of 5 would fight over one row.
ALTER TABLE similarity_relation
	DROP CONSTRAINT uk_similarity_relation;

ALTER TABLE similarity_relation
	ADD CONSTRAINT uk_similarity_relation UNIQUE (algorithm_id, max_distance, min_similarity, relation_digest,
		first_catalog_file_id, second_catalog_file_id);

ALTER TABLE similarity_relation_coverage
	DROP CONSTRAINT pk_similarity_relation_coverage;

ALTER TABLE similarity_relation_coverage
	ADD CONSTRAINT pk_similarity_relation_coverage PRIMARY KEY (algorithm_id, max_distance, min_similarity,
		relation_digest, catalog_file_id);

-- Reading is still "every relation of this parameter set", and the set now has
-- four components.
DROP INDEX ix_similarity_relation_parameters;

CREATE INDEX ix_similarity_relation_parameters
	ON similarity_relation (algorithm_id, max_distance, min_similarity, relation_digest);