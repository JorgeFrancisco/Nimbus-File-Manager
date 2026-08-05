-- The metadata rebuild stops being a thread with fields.
--
-- Whether one was running, how far it had got and what the last one found were
-- three AtomicReferences of the process that served the settings screen. That
-- worked while the pass ran there; it does not, because the pass belongs to the
-- worker - and it never worked across a restart, which is the point at which a
-- rebuild of a hundred thousand files most wants to be asked about.
--
-- The real run needs no table: what the screen shows are counters the execution
-- already has, and a message it already carries. Only the dry run does, because
-- a dry run produces something to look at rather than something to count.
--
-- No publication protocol here, unlike the organization plan. The reading only
-- ever asks for the preview of a finished execution, so the execution's own
-- status is what makes these rows visible - a run that died halfway leaves rows
-- nobody asks for, and its retry replaces them.

CREATE TABLE metadata_rebuild_preview (
	execution_id BIGINT PRIMARY KEY,

	-- What was asked, repeated from the execution so the preview stays readable
	-- and explainable on its own.
	source_path VARCHAR(1024) NOT NULL,

	-- How many files the folder offers, how many the "continue where it stopped"
	-- cutoff is hiding, how many were actually read for this preview, and how many
	-- of those would end up with another date. The sample is what makes a dry run
	-- cheap: reading every file would cost exactly what the real run costs.
	candidates INTEGER NOT NULL DEFAULT 0,
	skipped_by_cutoff INTEGER NOT NULL DEFAULT 0,
	examined INTEGER NOT NULL DEFAULT 0,
	would_change INTEGER NOT NULL DEFAULT 0,

	CONSTRAINT fk_metadata_rebuild_preview_execution FOREIGN KEY (execution_id)
		REFERENCES execution (id) ON DELETE CASCADE
);

CREATE TABLE metadata_rebuild_preview_item (
	execution_id BIGINT NOT NULL,

	-- The order the sample was read in, which is what makes the listing mean the
	-- same thing twice.
	ordinal INTEGER NOT NULL,

	path VARCHAR(1024) NOT NULL,

	-- Named with the _time suffix because "current_date" and "new_date" would
	-- collide with the SQL reserved word, and a column that needs quoting forever
	-- is a column named wrong once.
	current_date_time TIMESTAMP,
	new_date_time TIMESTAMP,

	-- The source as the enum, never as the sentence the screen shows: a row
	-- written under one language and read under another would otherwise answer in
	-- the language of whoever happened to run it.
	current_source VARCHAR(40),
	new_source VARCHAR(40),

	CONSTRAINT pk_metadata_rebuild_preview_item PRIMARY KEY (execution_id, ordinal),
	CONSTRAINT fk_metadata_rebuild_preview_item_preview FOREIGN KEY (execution_id)
		REFERENCES metadata_rebuild_preview (execution_id) ON DELETE CASCADE
);