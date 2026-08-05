-- The similarity result stops living in a HashMap.
--
-- It was computed on request, kept in the memory of whichever process computed
-- it, and lost on restart - which also meant two processes could each compute
-- their own, and neither could see the other's. Making the worker the only
-- engine requires the result to outlive the process that produced it, and that
-- is what these three tables are.
--
-- Modelled by validity, not by execution. An Execution is the lifecycle of one
-- attempt - who asked, how far it got, whether it was claimed, cancelled or
-- crashed - and it is retained on the executions screen by its own rules. A
-- grouping is a result of the domain: still useful long after the row that
-- produced it was cleaned up, so deleting an execution must never delete an
-- answer somebody is still looking at.

CREATE TABLE similarity_grouping (
	id BIGSERIAL PRIMARY KEY,
	public_id UUID NOT NULL UNIQUE,

	-- The family: which results compete for being the answer. The composition of
	-- the library is deliberately absent - a photo arriving does not make the
	-- published result stop being an answer, it makes a newer one possible.
	media_type VARCHAR(20) NOT NULL,
	algorithm_id VARCHAR(100) NOT NULL,
	grouping_version INTEGER NOT NULL,
	parameters_digest VARCHAR(64) NOT NULL,

	-- The composition: what this result is actually about. The digest covers the
	-- ids that entered the algorithm and the folder each was in, so a one-for-one
	-- swap of members, or a file moved into an excluded folder, changes it even
	-- when every count stays the same.
	composition_digest VARCHAR(64) NOT NULL,
	eligible_count INTEGER NOT NULL,
	analyzed_count INTEGER NOT NULL,
	candidate_limit INTEGER NOT NULL,
	selection_policy VARCHAR(40) NOT NULL,

	status VARCHAR(20) NOT NULL,
	computed_at TIMESTAMP NOT NULL,
	published_at TIMESTAMP,
	execution_id BIGINT,
	group_count INTEGER NOT NULL DEFAULT 0,
	member_count INTEGER NOT NULL DEFAULT 0,

	CONSTRAINT fk_similarity_grouping_execution FOREIGN KEY (execution_id)
		REFERENCES execution (id) ON DELETE SET NULL
);

-- One published answer per family, enforced by the database rather than by a
-- read followed by a write.
--
-- The Java version of this - "is there an ACTIVE? no? then insert one" - is
-- correct only until two workers ask at the same instant, and the whole point of
-- the migration is that there can be two. A partial unique index makes the
-- second publication fail instead of producing a library with two current
-- answers, and it costs nothing on BUILDING and SUPERSEDED rows, which are
-- expected to repeat.
CREATE UNIQUE INDEX ux_similarity_grouping_active
	ON similarity_grouping (media_type, algorithm_id, grouping_version, parameters_digest)
 WHERE status = 'ACTIVE';

-- Reading the current answer, which is the only query the screen makes.
CREATE INDEX ix_similarity_grouping_family
	ON similarity_grouping (media_type, algorithm_id, grouping_version, parameters_digest, status);

-- Finding groupings abandoned mid-build, and the retention sweep over retired
-- ones.
CREATE INDEX ix_similarity_grouping_status_computed
	ON similarity_grouping (status, computed_at);

CREATE TABLE similarity_group (
	id BIGSERIAL PRIMARY KEY,
	grouping_id BIGINT NOT NULL,

	-- What the badge shows: the lowest pairwise score inside the group. Kept as
	-- computed rather than recomputed on read, because the fingerprints it was
	-- derived from may be gone by the time somebody looks.
	similarity_percent INTEGER NOT NULL,
	file_count INTEGER NOT NULL,
	wasted_bytes BIGINT NOT NULL,

	-- The order the screen shows, decided once by the analysis (largest waste
	-- first) so paginating cannot reshuffle what page two means.
	position INTEGER NOT NULL,

	CONSTRAINT fk_similarity_group_grouping FOREIGN KEY (grouping_id)
		REFERENCES similarity_grouping (id) ON DELETE CASCADE
);

CREATE INDEX ix_similarity_group_grouping_position
	ON similarity_group (grouping_id, position);

CREATE TABLE similarity_group_member (
	id BIGSERIAL PRIMARY KEY,
	group_id BIGINT NOT NULL,

	-- The file by public id, which is what the screen and the deletion already
	-- speak. Not a foreign key to catalog_file on purpose: a member whose file was
	-- deleted or quarantined after publication must not delete the group it was
	-- part of - the reading decides what to do with a member that is no longer
	-- usable, and a cascade would decide it here, wrongly.
	media_public_id UUID NOT NULL,

	-- The verdict this analysis reached for the file, stored because it is part of
	-- the result: which one to keep and why is what the user acts on.
	verdict VARCHAR(20) NOT NULL,
	reason VARCHAR(40),
	position INTEGER NOT NULL,

	CONSTRAINT fk_similarity_group_member_group FOREIGN KEY (group_id)
		REFERENCES similarity_group (id) ON DELETE CASCADE
);

CREATE INDEX ix_similarity_group_member_group_position
	ON similarity_group_member (group_id, position);

CREATE INDEX ix_similarity_group_member_media
	ON similarity_group_member (media_public_id);