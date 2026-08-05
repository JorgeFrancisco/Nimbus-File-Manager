-- The inventory kept two records of every run: an Execution, which the product
-- shows and the queue now claims, and a Spring Batch JobInstance/JobExecution/
-- StepExecution, which nothing outside the framework ever read.
--
-- The one capability that would have justified the second record is restarting
-- a run from a checkpoint. It was never in use: the reader opened an
-- ExecutionContext and persisted no cursor, which is why every boot marked an
-- unfinished run INTERRUPTED instead of resuming it. A full inventory pass is
-- idempotent, so running it again is the recovery - and paying for a job
-- repository to not use it was the whole cost of the framework.
--
-- Nothing is transported. These tables describe how the old engine ran, not
-- what it found: the files it catalogued live in catalog_file, and the history
-- of the runs themselves lives in execution, both untouched by this migration.

DROP TABLE IF EXISTS BATCH_STEP_EXECUTION_CONTEXT;
DROP TABLE IF EXISTS BATCH_JOB_EXECUTION_CONTEXT;
DROP TABLE IF EXISTS BATCH_STEP_EXECUTION;
DROP TABLE IF EXISTS BATCH_JOB_EXECUTION_PARAMS;
DROP TABLE IF EXISTS BATCH_JOB_EXECUTION;
DROP TABLE IF EXISTS BATCH_JOB_INSTANCE;

DROP SEQUENCE IF EXISTS BATCH_STEP_EXECUTION_SEQ;
DROP SEQUENCE IF EXISTS BATCH_JOB_EXECUTION_SEQ;
DROP SEQUENCE IF EXISTS BATCH_JOB_SEQ;