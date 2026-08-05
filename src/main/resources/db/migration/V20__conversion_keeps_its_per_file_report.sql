-- The conversion report - one line per video, with what it became and how much
-- it saved - existed only as an object returned from the batch to the request
-- that started it. That worked while both were the same process. With the
-- encoding claimed by the worker there is nothing to return to: the screen asks
-- afterwards, from another process, and the answer has to be somewhere it can
-- read.
--
-- Only what the report actually renders is kept: the file, the outcome, the two
-- sizes, the explanation, and the four flags the detail column spells out. What
-- is saved is not stored - it is the difference between the two sizes, and a
-- third column holding a subtraction is a column that can disagree with its own
-- operands.
--
-- Deliberately not movement. A movement means the application moved a file from
-- one place to another and is read as such by the history, the undo and the
-- integrity summary; a converted video that was never moved anywhere would
-- become a movement nobody could undo. Reporting is not that, and borrowing the
-- table would have cost the domain its meaning.
--
-- Failures stay in execution_error, which is theirs. The screen reads both and
-- shows one table, which is a presentation decision and belongs there.
--
-- Retention needs no code: the row belongs to its execution and goes when the
-- execution goes, by the same cascade the movements and the steps already use.

CREATE TABLE conversion_item_result (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    execution_id BIGINT NOT NULL,
    media_public_id UUID,
    file_name VARCHAR(512) NOT NULL,
    outcome VARCHAR(30) NOT NULL,
    original_bytes BIGINT NOT NULL DEFAULT 0,
    converted_bytes BIGINT NOT NULL DEFAULT 0,
    message TEXT,
    audio_fallback BOOLEAN NOT NULL DEFAULT FALSE,
    subtitles_dropped BOOLEAN NOT NULL DEFAULT FALSE,
    data_dropped BOOLEAN NOT NULL DEFAULT FALSE,
    original_quarantined BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_conversion_item_result_execution
        FOREIGN KEY (execution_id) REFERENCES execution (id)
        ON DELETE CASCADE
);

-- The only question ever asked of it: the lines of one batch, in the order they
-- happened.
CREATE INDEX ix_conversion_item_result_execution
    ON conversion_item_result (execution_id, id);