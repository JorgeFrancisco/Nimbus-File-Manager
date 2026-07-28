-- Why a file has no fingerprint, as a column instead of a marker inside the error
-- text. "[unsupported] " was prepended to last_error and matched with LIKE to keep
-- terminal failures out of the counts; a real column classifies every failure, not
-- just that one case, and stops the screen from showing the marker to the user.
ALTER TABLE fingerprint_failure ADD COLUMN reason VARCHAR(30);

UPDATE fingerprint_failure
   SET reason = 'UNSUPPORTED_FORMAT',
       last_error = btrim(substring(last_error FROM length('[unsupported] ') + 1))
 WHERE last_error LIKE '[unsupported]%';

-- The rest cannot be classified here: the reason comes from the file's own bytes,
-- which only a new attempt reads. Their attempts are cleared so the backlog picks
-- them up once more and records a real reason - without this they would sit as
-- UNKNOWN forever, since an exhausted row is never fetched again. The extra pass
-- costs one decode per file and then every terminal one stops being tried at all.
UPDATE fingerprint_failure SET reason = 'UNKNOWN', attempts = 0 WHERE reason IS NULL;

ALTER TABLE fingerprint_failure ALTER COLUMN reason SET NOT NULL;

CREATE INDEX idx_fingerprint_failure_reason ON fingerprint_failure (kind, algorithm, reason);