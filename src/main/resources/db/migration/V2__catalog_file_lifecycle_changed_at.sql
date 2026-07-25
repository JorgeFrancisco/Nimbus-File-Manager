-- ============================================================
-- catalog_file.lifecycle_changed_at
-- Anchor timestamp for the lifecycle_status state machine, set whenever a file
-- transitions to a new state (ACTIVE <-> MISSING -> DELETED). It gives the
-- catalog retention purge a date to age MISSING records against; without it a
-- MISSING row has no "since when" to measure the retention window from.
-- ============================================================
ALTER TABLE catalog_file ADD COLUMN lifecycle_changed_at TIMESTAMP;

-- Backfill existing non-ACTIVE rows with the migration moment (DB clock; the app
-- clock is used from now on) so the retention window has an anchor and they age
-- out N days from now - a grace period - instead of being purged on the first
-- run or lingering forever with a NULL anchor. ACTIVE rows stay NULL and get a
-- value only when they first transition.
UPDATE catalog_file SET lifecycle_changed_at = CURRENT_TIMESTAMP WHERE lifecycle_status <> 'ACTIVE';

CREATE INDEX ix_catalog_file_lifecycle_changed_at ON catalog_file(lifecycle_status, lifecycle_changed_at);