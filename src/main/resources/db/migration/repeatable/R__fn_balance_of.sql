-- An account's balance strictly before an instant: the nearest preceding anchor plus the
-- operations after it (§4.6). The bound is exclusive so a caller can say "the whole of 15 March"
-- as `16 March 00:00` without depending on timestamp precision. The `p_` prefix keeps a parameter
-- from resolving to the column of that name.
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
