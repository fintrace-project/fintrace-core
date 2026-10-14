ALTER TABLE t_workspaces
    ADD COLUMN import_job_id uuid;

ALTER TABLE t_workspaces
    ADD CONSTRAINT fk_t_workspaces_import_job_id
        FOREIGN KEY (import_job_id) REFERENCES t_import_jobs (id);

UPDATE t_workspaces w
SET import_job_id = (
    SELECT j.id
    FROM t_import_jobs j
    WHERE j.workspace_id = w.id AND j.status = 'RUNNING'
    ORDER BY j.started_at DESC
    LIMIT 1
)
WHERE w.status = 'IMPORTING';

ALTER TABLE t_workspaces
    ADD CONSTRAINT chk_t_workspaces_import_job_id
        CHECK ((status = 'IMPORTING') = (import_job_id IS NOT NULL));
