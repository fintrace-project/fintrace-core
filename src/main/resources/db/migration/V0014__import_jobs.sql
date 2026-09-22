CREATE TABLE t_import_jobs
(
    id               uuid PRIMARY KEY,
    workspace_id     uuid        NOT NULL,
    status           varchar(60) NOT NULL,
    started_by       uuid        NOT NULL,
    started_at       timestamp   NOT NULL,
    finished_at      timestamp,
    importer_name    text        NOT NULL,
    importer_version text        NOT NULL,
    message          text,
    accounts         integer,
    categories       integer,
    operations       integer,
    transfers        integer,
    anchors          integer,
    diagnostics      jsonb,

    CONSTRAINT chk_t_import_jobs_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_t_import_jobs_finished_at
        CHECK ((status = 'RUNNING') = (finished_at IS NULL)),
    CONSTRAINT fk_t_import_jobs_workspace_id
        FOREIGN KEY (workspace_id) REFERENCES t_workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_t_import_jobs_started_by
        FOREIGN KEY (started_by) REFERENCES t_users (id)
);

CREATE INDEX idx_t_import_jobs_workspace_id_started_at
    ON t_import_jobs (workspace_id, started_at DESC);
