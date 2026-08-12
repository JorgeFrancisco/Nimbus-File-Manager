-- The remaining-time estimate stops being a cumulative average and becomes a mean
-- over a recent window. The window is carried by the row, and not by the process
-- doing the work, for two reasons: the screen renders in the application while the
-- work happens in the worker, so memory in one is invisible to the other; and a
-- measurement that survives neither restart nor reclaim would restart the estimate
-- every time it is needed most.
--
-- Two marks rather than one. A single anchor rolled forward would reset the span to
-- zero each time it moved, so the estimate would drop back to "calculating" once per
-- window forever. With an older mark and a newer one, the newer becomes the older
-- when it ages past the window, and the span measured is always at least a window
-- wide.
--
-- Purely additive: no existing value changes meaning or shape, so there is nothing
-- to carry across. Runs already in flight when this applies simply report
-- "calculating" until their first mark is written, which is the correct answer for a
-- measurement that has not started.
ALTER TABLE execution ADD COLUMN rate_window_from_at   TIMESTAMP;
ALTER TABLE execution ADD COLUMN rate_window_from_done INTEGER;
ALTER TABLE execution ADD COLUMN rate_window_mark_at   TIMESTAMP;
ALTER TABLE execution ADD COLUMN rate_window_mark_done INTEGER;