-- The table was named for the inventory's analysis phase, the only thing that
-- wrote to it. Conversions and duplicate deletions record their per-file
-- failures here too, so the name described one writer instead of the content:
-- a file that could not be processed inside an execution.
--
-- Every constraint and index is renamed with it. A half-renamed schema is worse
-- than the old name: the next person greps for the new word and believes what
-- comes back.
ALTER TABLE analysis_error RENAME TO execution_error;

ALTER TABLE execution_error RENAME CONSTRAINT analysis_error_pkey TO execution_error_pkey;
ALTER TABLE execution_error RENAME CONSTRAINT uk_analysis_error_public_id TO uk_execution_error_public_id;
ALTER TABLE execution_error RENAME CONSTRAINT fk_analysis_error_execution TO fk_execution_error_execution;

ALTER INDEX ix_analysis_error_execution RENAME TO ix_execution_error_execution;
ALTER INDEX ix_analysis_error_path RENAME TO ix_execution_error_path;
ALTER INDEX ix_analysis_error_created_at RENAME TO ix_execution_error_created_at;
ALTER INDEX ix_analysis_error_type RENAME TO ix_execution_error_type;
ALTER INDEX ix_analysis_error_execution_created RENAME TO ix_execution_error_execution_created;
ALTER INDEX ix_analysis_error_type_path_created RENAME TO ix_execution_error_type_path_created;
ALTER INDEX ix_analysis_error_lower_path RENAME TO ix_execution_error_lower_path;