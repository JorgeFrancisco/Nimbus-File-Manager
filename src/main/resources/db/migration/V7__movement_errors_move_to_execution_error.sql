-- A failed move was recorded in two shapes: the movement row said ERROR and
-- carried the reason, while every other operation put its per-file failures in
-- execution_error. Two places to look for one question - "which file failed and
-- why" - and the execution screen only showed one of them.
--
-- The movement row stays: it records the relocation that was attempted, where
-- from and where to, which is what the undo reads. Only the reason moves.
-- Whatever an installation already recorded comes across. movement.execution_id
-- is NOT NULL, so every message has an execution to belong to.
--
-- Blank is not a reason: a row saying a file failed without saying why repeats
-- what the movement's own ERROR status already shows, and it would occupy the
-- error list of an execution while answering nothing.
INSERT INTO execution_error (public_id, execution_id, path, error_type, error_message, created_at)
SELECT gen_random_uuid(), m.execution_id, m.source_path, 'MOVE_ERROR', m.error_message, m.moved_at
FROM movement m
WHERE m.error_message IS NOT NULL
  AND btrim(m.error_message) <> '';

ALTER TABLE movement DROP COLUMN error_message;