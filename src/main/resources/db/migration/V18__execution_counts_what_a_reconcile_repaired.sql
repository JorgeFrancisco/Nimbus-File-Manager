-- A reconcile finished with every counter at zero, however much it had repaired.
-- What it did lived only in the status message - as a message code plus its
-- arguments - so nothing could ask "how many items did this execution change?"
-- without reading a translation key, and the history screen had no way to tell a
-- pass that fixed three paths from one that found nothing to do.
--
-- repaired_items answers that. It is content, not presentation: the number of
-- catalog entries a reconcile actually corrected, whether by following a rename,
-- by syncing a stale path, or by marking a file missing. The screen happens to
-- use it to hide the automatic passes that changed nothing, but the column would
-- mean the same thing if it never did.
--
-- Deliberately not filesMoved. That counter means "the application moved these
-- files on disk" everywhere else - organization, undo, deduplication - and a
-- reconcile moves nothing: it records moves the user made outside the
-- application. Reusing it would make the screen say "Movidos: 3" for a run that
-- touched no file at all.
--
-- Existing rows keep zero, which is what they were: the counters were never
-- filled for reconcile, so no history is being rewritten - only the shape is
-- there for what comes next.

ALTER TABLE execution ADD COLUMN repaired_items INTEGER NOT NULL DEFAULT 0;

-- The history screen hides automatic reconciles that repaired nothing, and asks
-- exactly this: type, trigger and the counter. Partial, because the rows it
-- serves are a small part of a table that grows with every run.
CREATE INDEX ix_execution_reconcile_repairs
    ON execution (trigger_event, repaired_items)
 WHERE execution_type = 'RECONCILE';