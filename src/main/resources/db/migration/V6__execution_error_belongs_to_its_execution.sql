-- An error row survived the execution that produced it: the foreign key set the
-- column to null instead of removing the row, so deleting history left failures
-- with no operation, no folder and no date to belong to. Nothing reads them and
-- nothing can - "which file failed?" is only answerable inside an execution.
--
-- Every other child of execution - metrics, movements, steps, phases - already
-- cascades. This one was the exception, not the rule.
DELETE FROM execution_error WHERE execution_id IS NULL;

ALTER TABLE execution_error DROP CONSTRAINT fk_execution_error_execution;

ALTER TABLE execution_error ALTER COLUMN execution_id SET NOT NULL;

ALTER TABLE execution_error
    ADD CONSTRAINT fk_execution_error_execution
    FOREIGN KEY (execution_id)
    REFERENCES execution(id)
    ON DELETE CASCADE;