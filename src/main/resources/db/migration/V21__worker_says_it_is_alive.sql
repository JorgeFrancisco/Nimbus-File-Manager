-- Whether anything is there to do the work.
--
-- Until now that answer lived in WorkerSupervisor.isRunning(), which reads a
-- Process handle in the application's own memory. It answers for the child this
-- process started, and for nothing else: a worker started by hand, a worker that
-- lost the database, or a worker whose supervisor was restarted are all invisible
-- to it - and the application had no way to tell "your work is queued and being
-- processed" from "your work is queued and nobody is going to pick it up".
--
-- Two columns, because two questions are all that has a consumer today. Who the
-- instance is, so more than one can be noticed rather than averaged into one
-- number; and when it was last heard from, so freshness is decided by whoever
-- asks instead of being frozen into a boolean here. started_at, version,
-- hostname and pid were considered and left out: nothing reads them, the
-- supervisor already knows when it started its child, and the schema check
-- already refuses a worker built for another database. A column nobody reads is
-- a column that goes stale without anyone noticing.
--
-- Not a lease, and deliberately not shaped like one. A lease says who owns one
-- execution and is renewed per execution; this says a process is alive at all.
-- Reusing either for the other would make an idle worker look dead, or a dead
-- worker look like it still owns what it was running.
CREATE TABLE worker_instance (
	worker_id VARCHAR(128) PRIMARY KEY,
	last_seen_at TIMESTAMP NOT NULL
);