-- The bound is exclusive, so `16 March 00:00` means the whole of 15 March. `p_` keeps a
-- parameter from resolving to a column.
DROP FUNCTION IF EXISTS fn_balance_of(uuid, uuid, timestamp);

CREATE
OR REPLACE FUNCTION fn_balance_of(
    p_workspace_id uuid,
    p_account_id uuid,
    p_until timestamp
) RETURNS numeric(19, 4)
    LANGUAGE sql
    STABLE
    PARALLEL SAFE
AS
$$
WITH anchor AS (SELECT value, occurred_at
                FROM t_balance_anchors
                WHERE workspace_id = p_workspace_id
                  AND account_id = p_account_id
                  AND occurred_at < p_until
                ORDER BY occurred_at DESC, id DESC
                LIMIT 1)
SELECT (
           COALESCE((SELECT value FROM anchor), 0)
               + COALESCE((SELECT SUM(amount)
                           FROM t_operations
                           WHERE workspace_id = p_workspace_id
                             AND account_id = p_account_id
                             AND occurred_at < p_until
                             AND occurred_at > COALESCE((SELECT occurred_at FROM anchor),
                                                        '-infinity' ::timestamp)), 0)
           ) ::numeric(19, 4);
$$;
