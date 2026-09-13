-- What an anchor absorbed: its observed value minus the balance the operations explain at that
-- moment (§4.6). Takes the anchor's id, so it cannot be counted as its own baseline.
DROP FUNCTION IF EXISTS fn_unexplained_difference_of(uuid, uuid);

CREATE
OR REPLACE FUNCTION fn_unexplained_difference_of(
    p_workspace_id uuid,
    p_anchor_id uuid
) RETURNS numeric(19, 4)
    LANGUAGE sql
    STABLE
    PARALLEL SAFE
AS
$$
WITH this AS (SELECT account_id, value, occurred_at
              FROM t_balance_anchors
              WHERE workspace_id = p_workspace_id
                AND id = p_anchor_id),
     previous AS (SELECT a.value, a.occurred_at
                  FROM t_balance_anchors a,
                       this
                  WHERE a.workspace_id = p_workspace_id
                    AND a.account_id = this.account_id
                    AND a.occurred_at < this.occurred_at
                  ORDER BY a.occurred_at DESC
                  LIMIT 1)
SELECT (
           (SELECT value FROM this)
               - COALESCE((SELECT value FROM previous), 0)
               - COALESCE((SELECT SUM(o.amount)
                           FROM t_operations o,
                                this
                           WHERE o.workspace_id = p_workspace_id
                             AND o.account_id = this.account_id
                             AND o.occurred_at < this.occurred_at
                             AND o.occurred_at > COALESCE((SELECT occurred_at FROM previous),
                                                          '-infinity' ::timestamp)), 0)
           ) ::numeric(19, 4);
$$;
