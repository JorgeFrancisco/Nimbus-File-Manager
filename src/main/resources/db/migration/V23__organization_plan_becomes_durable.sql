-- The organization preview stops living in a LinkedHashMap.
--
-- It was computed by the application, kept in the memory of the process that
-- served the screen, evicted once five newer plans arrived, and lost on restart.
-- Worse than losing it: producing it meant the application composing the class
-- that can move the user's files, which is the last reason the mutation-port
-- exception list still names OrganizationService.
--
-- Two tables, and the shape of the first says the essential thing: the primary
-- key IS the execution id. One plan per execution, and no plan without one.
--
-- A plan is not history. The execution that produced it is - "a preview was
-- asked for" belongs in the executions screen and keeps that screen's retention
-- - but the plan itself exists for somebody to look at and decide, so it
-- carries its own, much shorter expiry and is swept without touching the
-- execution it belongs to.

CREATE TABLE organization_plan (
	execution_id BIGINT PRIMARY KEY,

	-- What was asked. Repeated from the execution's own columns on purpose: the
	-- plan has to be readable, and explainable, after the retention sweep has
	-- taken the execution away.
	source_path VARCHAR(1024) NOT NULL,
	target_path VARCHAR(1024) NOT NULL,
	layout VARCHAR(40) NOT NULL,

	-- BUILDING while the rows are being written, READY once the count checks out,
	-- FAILED when the run could not finish one. The screen only ever reads READY,
	-- so nothing partial is visible - that is the whole publication protocol.
	status VARCHAR(12) NOT NULL,

	-- The summary, so the screen never counts a hundred thousand rows to render a
	-- card.
	item_count INTEGER NOT NULL DEFAULT 0,
	conflict_count INTEGER NOT NULL DEFAULT 0,
	planned_moves INTEGER NOT NULL DEFAULT 0,
	total_size_bytes BIGINT NOT NULL DEFAULT 0,

	-- The catalog as it was when this plan was produced. The execute recalculates
	-- and deliberately does not read the plan, so the two can disagree; this is
	-- what lets the screen say so instead of letting the user find out afterwards.
	catalog_signature VARCHAR(120),

	built_at TIMESTAMP,

	-- Its own expiry, shorter than the execution's retention. An expired plan is
	-- deleted with its items and the execution stays in the history, which is the
	-- difference between an artifact to look at and a fact that happened.
	expires_at TIMESTAMP NOT NULL,

	CONSTRAINT fk_organization_plan_execution FOREIGN KEY (execution_id)
		REFERENCES execution (id) ON DELETE CASCADE
);

-- The sweep of expired plans, and of the BUILDING residue a worker that died
-- left behind.
CREATE INDEX ix_organization_plan_expires ON organization_plan (expires_at);
CREATE INDEX ix_organization_plan_status ON organization_plan (status, built_at);

CREATE TABLE organization_plan_item (
	execution_id BIGINT NOT NULL,

	-- The order the planner decided, which is what makes a page mean the same
	-- thing twice. It is half the primary key rather than a surrogate id: the
	-- pagination is a keyset scan over exactly this.
	ordinal INTEGER NOT NULL,

	-- The file by public id, like every other durable result in this codebase.
	-- Not a foreign key to catalog_file: a file deleted after the plan was built
	-- must not delete rows out of a plan somebody is reading, and the reading
	-- already knows how to show an item whose file is gone.
	catalog_file_id UUID NOT NULL,

	file_name VARCHAR(512) NOT NULL,
	source_path VARCHAR(1024) NOT NULL,
	target_path VARCHAR(1024) NOT NULL,
	size_bytes BIGINT,

	location VARCHAR(255),
	location_confidence VARCHAR(40),

	conflict BOOLEAN NOT NULL DEFAULT FALSE,
	conflict_type VARCHAR(40),

	CONSTRAINT pk_organization_plan_item PRIMARY KEY (execution_id, ordinal),
	CONSTRAINT fk_organization_plan_item_plan FOREIGN KEY (execution_id)
		REFERENCES organization_plan (execution_id) ON DELETE CASCADE
);

-- "Only conflicts" over a plan of a hundred thousand items, without reading the
-- ones that are fine. Partial because the conflicted rows are the small minority
-- and the index has no reason to carry the rest.
CREATE INDEX ix_organization_plan_item_conflict
	ON organization_plan_item (execution_id, ordinal)
 WHERE conflict;