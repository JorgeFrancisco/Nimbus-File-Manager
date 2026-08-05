-- Execution stops being a report of what a thread did and becomes the queue
-- itself. Until now the row was written by whoever happened to be running the
-- work: a pool thread picked the job up, and the row appeared already started.
-- Nothing outside that JVM could see the intent, so a restart lost every
-- pending request and two processes could never share the load.
--
-- From here the row exists before the work does. PENDING is a request nobody
-- has taken, RUNNING is a request a worker owns, and ownership is expressed by
-- claimed_by plus lease_until - data, not a database lock, so no transaction
-- stays open while files are moving.
--
-- The status column was doing two jobs. STARTED, SCANNING_FILES and
-- PROCESSING_FILES describe what an execution is busy with; FINISHED, ERROR
-- and the rest describe how it ended. Only the second kind is a lifecycle. The
-- first moves to phase, which is free to be null and free to grow without ever
-- touching the queue predicate.
--
-- Existing rows keep their meaning: the three active states were, by
-- definition, executions that were running when the application last stopped -
-- the same thing StartupExecutionRecoveryListener already marked INTERRUPTED
-- on every boot - and their old state survives as the phase they had reached.

ALTER TABLE execution
    ADD COLUMN phase VARCHAR(30),
    ADD COLUMN created_at TIMESTAMP,
    ADD COLUMN claimed_by VARCHAR(100),
    ADD COLUMN claimed_at TIMESTAMP,
    ADD COLUMN lease_until TIMESTAMP,
    ADD COLUMN priority INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN claim_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN available_at TIMESTAMP,
    ADD COLUMN dedup_key VARCHAR(1024),
    ADD COLUMN request_payload JSONB;

-- An execution that already ran was requested when it started; there is no
-- earlier instant on record, and inventing one would put false ages into the
-- fairness expression.
UPDATE execution SET created_at = started_at, available_at = started_at;

ALTER TABLE execution
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN available_at SET NOT NULL;

-- The phase an interrupted execution had reached is worth keeping - it is the
-- difference between "it was still listing files" and "it was already moving
-- them" when the power went out.
UPDATE execution SET phase = 'SCANNING' WHERE status = 'SCANNING_FILES';
UPDATE execution SET phase = 'PROCESSING' WHERE status = 'PROCESSING_FILES';

UPDATE execution
   SET status = 'INTERRUPTED',
       claim_count = 1
 WHERE status IN ('STARTED', 'SCANNING_FILES', 'PROCESSING_FILES');

-- started_at only means something once a worker has taken the row, and a
-- PENDING execution has not started by definition.
ALTER TABLE execution ALTER COLUMN started_at DROP NOT NULL;

-- Two partial indexes rather than one over both states: a single unique index
-- on (type, key) WHERE status IN ('PENDING','RUNNING') would forbid exactly
-- the case that replaces the old in-memory inventoryPending flag - one scan
-- running while the next request waits its turn. One index caps the waiting
-- row, the other caps the running row, and together they allow 1 + 1. A null
-- dedup_key opts out entirely, which is how the types that accept repeated
-- requests (a conversion, an undo) coexist with the same DDL.
CREATE UNIQUE INDEX ux_execution_pending_dedup
    ON execution (execution_type, dedup_key)
 WHERE status = 'PENDING' AND dedup_key IS NOT NULL;

CREATE UNIQUE INDEX ux_execution_running_dedup
    ON execution (execution_type, dedup_key)
 WHERE status = 'RUNNING' AND dedup_key IS NOT NULL;

-- The claim scans pending rows and orders them by an expression involving
-- now(), which no index can satisfy. What this one does is keep the scanned
-- set to the queue itself instead of a history table that grows forever.
CREATE INDEX ix_execution_pending_claim
    ON execution (priority DESC, id)
 WHERE status = 'PENDING';

CREATE INDEX ix_execution_running_lease
    ON execution (lease_until)
 WHERE status = 'RUNNING';

-- Paths the application is about to write, so the watcher does not answer its
-- own output with a full inventory of the library. This used to be a map in
-- the JVM that also did the writing; once the writing moves to another
-- process, memory is the one place both sides cannot see.
--
-- Read, never consumed on first match. A single write raises several
-- notifications - name, size, last write - and a long one spreads them across
-- polls, so deleting the announcement on the first event would leave every
-- later event looking foreign. The entry goes away when its execution ends, or
-- by TTL, so nothing can silence a path indefinitely.
CREATE TABLE self_written_path (
    path_key VARCHAR(1024) PRIMARY KEY,
    execution_id BIGINT,
    announced_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_self_written_path_execution
        FOREIGN KEY (execution_id) REFERENCES execution(id) ON DELETE CASCADE
);

CREATE INDEX ix_self_written_path_execution ON self_written_path (execution_id);
CREATE INDEX ix_self_written_path_announced ON self_written_path (announced_at);