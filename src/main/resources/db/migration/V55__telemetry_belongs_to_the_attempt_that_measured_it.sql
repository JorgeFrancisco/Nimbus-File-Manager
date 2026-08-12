-- ============================================================
-- Telemetry that survives a restart, and an attempt that owns it
-- ============================================================
--
-- Three things were wrong at once.
--
-- The aggregate had nowhere to put what the run actually measured: gate waits,
-- external runs, queue time and task counts lived in memory and died with the
-- process, so a finished execution could be asked how long it took and nothing
-- else. What did have columns - the photo-hash counters - had no writer in
-- production for as long as they existed, so every row carried three zeroes
-- that meant nothing.
--
-- Nothing said which attempt wrote a row. An execution recovered and claimed
-- again produced a second consolidation, and the two were indistinguishable:
-- last writer won, and the last writer could be the one that had already been
-- replaced. attempt_claim_count is the fencing token that settles it.
--
-- Per-category cost had no shape at all. A photo hash and a video probe are
-- different tools with different limits, and folding both into one row is how a
-- report says "ffmpeg took an hour" without saying which ffmpeg.
--
-- No data is carried across. Telemetry describes a run that is over; it is
-- measurement, not the user's library, and this branch is a breaking change
-- whose database is discarded. The rows are deleted rather than backfilled
-- because there is no honest value for attempt_claim_count in a row written
-- before attempts were recorded - inventing one would make a stale write look
-- authoritative, which is the exact failure this column exists to prevent.

DELETE FROM execution_phase;
DELETE FROM execution_metrics;

-- ============================================================
-- execution_metrics: the aggregate of one attempt
-- ============================================================
ALTER TABLE execution_metrics
    DROP COLUMN photo_hash_jvm_decodable,
    DROP COLUMN photo_hash_ffmpeg_only,
    DROP COLUMN photo_hash_failures;

ALTER TABLE execution_metrics
    ADD COLUMN attempt_claim_count INTEGER NOT NULL,

    ADD COLUMN tasks_executed BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN tasks_cache_avoided BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN tasks_cancelled BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN tasks_error BIGINT NOT NULL DEFAULT 0,

    ADD COLUMN queue_wait_millis BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN task_total_millis BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN batch_wall_clock_millis BIGINT NOT NULL DEFAULT 0,

    ADD COLUMN max_concurrency INTEGER NOT NULL DEFAULT 0;

-- ============================================================
-- execution_phase: one row per macro phase, per execution
-- ============================================================
--
-- Consolidation replaces what a previous attempt of the same row wrote, so a
-- phase can only be there once. Without this the replacement was a promise the
-- schema did not keep: a second attempt appended, and the screen summed the two.
ALTER TABLE execution_phase
    ADD CONSTRAINT ux_execution_phase_execution_phase UNIQUE (execution_id, phase);

-- ============================================================
-- execution_metrics_category: cost per external tool category
-- ============================================================
--
-- Typed columns rather than a JSON blob or a key/value pair: the three numbers
-- are the same three for every category, they are queried and compared by the
-- Statistics screen, and a category is an ExternalToolCategory rather than a
-- label somebody typed.
CREATE TABLE execution_metrics_category (
    id BIGSERIAL PRIMARY KEY,

    execution_id BIGINT NOT NULL,

    category VARCHAR(30) NOT NULL,

    runs BIGINT NOT NULL DEFAULT 0,
    gate_wait_millis BIGINT NOT NULL DEFAULT 0,
    external_exec_millis BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_execution_metrics_category_execution
        FOREIGN KEY (execution_id)
        REFERENCES execution(id)
        ON DELETE CASCADE,

    CONSTRAINT ux_execution_metrics_category UNIQUE (execution_id, category)
);

-- The Statistics screen reads every category of one execution at a time.
CREATE INDEX ix_execution_metrics_category_execution ON execution_metrics_category(execution_id);