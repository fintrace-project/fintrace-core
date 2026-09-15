ALTER TABLE t_events
    ADD COLUMN recorded_by uuid;

UPDATE t_events e
SET recorded_by = w.owner_id FROM t_workspaces w
WHERE w.id = e.workspace_id;

ALTER TABLE t_events
    ALTER COLUMN recorded_by SET NOT NULL,
    ADD CONSTRAINT fk_t_events_recorded_by
        FOREIGN KEY (recorded_by) REFERENCES t_users (id);