-- The fingerprint backlogs stop being a private job table.
--
-- fingerprint_job_run was the runner's own history: which backlog ran, when it
-- started and finished, how many items it processed and failed, and a message.
-- Every one of those is a column the execution table already has, and nothing
-- outside the runner ever read this one - the screen asks the runner, and the
-- recovery on startup marked its own leftovers as failed.
--
-- With the backlog claimed off the queue, the queue keeps that history, its
-- reclaim replaces the recovery, and this table has nothing left to represent.
--
-- The rows are not dropped: a run that happened, happened. Each becomes an
-- execution of the type that now performs it, so the executions screen shows the
-- fingerprint history alongside every other kind of work instead of it
-- disappearing with the table.

INSERT INTO execution (public_id, execution_type, status, started_at, finished_at, created_at,
                       available_at, recursive, execute_flag, files_found, files_analyzed,
                       cache_hits, files_moved, simulated_files, errors, message)
SELECT gen_random_uuid(),
       CASE WHEN kind = 'VIDEO_PHASH' THEN 'FINGERPRINT_VIDEO' ELSE 'FINGERPRINT_PHOTO' END,

       -- The old vocabulary had four words and the queue has more, but only these
       -- three ever reached this table.
       CASE status
           WHEN 'FINISHED' THEN 'FINISHED'
           WHEN 'CANCELLED' THEN 'CANCELLED'
           ELSE 'ERROR'
       END,

       started_at,
       finished_at,

       -- Created and available when it started: a run recorded here was never
       -- queued, so the only honest moment for both is the one it began.
       started_at,
       started_at,

       FALSE,
       TRUE,

       -- total_at_start is what it set out to do; processed and failed are what it
       -- did. The queue's names for the same three.
       total_at_start,
       processed,
       0,
       0,
       0,
       failed,

       message
FROM fingerprint_job_run;

DROP TABLE fingerprint_job_run;