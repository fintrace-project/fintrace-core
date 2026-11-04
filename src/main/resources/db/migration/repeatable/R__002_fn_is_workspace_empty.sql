DROP FUNCTION IF EXISTS fn_is_workspace_empty(uuid);

CREATE
OR REPLACE FUNCTION fn_is_workspace_empty(
    p_workspace_id uuid
) RETURNS BOOLEAN
    LANGUAGE sql
    STABLE
    PARALLEL SAFE
AS
$$
SELECT NOT EXISTS (SELECT 1 FROM t_accounts WHERE workspace_id = p_workspace_id)
           AND NOT EXISTS (SELECT 1 FROM t_operations WHERE workspace_id = p_workspace_id)
           AND NOT EXISTS (SELECT 1 FROM t_balance_anchors WHERE workspace_id = p_workspace_id)
           AND NOT EXISTS (SELECT 1 FROM t_categories WHERE workspace_id = p_workspace_id AND system_code IS NULL)
$$;
