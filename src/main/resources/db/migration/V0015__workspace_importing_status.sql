ALTER TABLE t_workspaces
    ADD COLUMN import_started_at timestamp;

ALTER TABLE t_workspaces
    DROP CONSTRAINT chk_t_workspaces_status;

ALTER TABLE t_workspaces
    ADD CONSTRAINT chk_t_workspaces_status
        CHECK (status IN ('NEW', 'IMPORTING', 'ACTIVE', 'ARCHIVED', 'DELETED'));

ALTER TABLE t_workspaces
    ADD CONSTRAINT chk_t_workspaces_import_started_at
        CHECK (status <> 'IMPORTING' OR import_started_at IS NOT NULL);
