-- One row per (category, currency): the magnitude of the operations of one kind booked to that category itself in the
-- period, its subcategories not rolled in. A category with nothing in a currency answers no row for it.
DROP FUNCTION IF EXISTS fn_category_totals(uuid, text, timestamp, timestamp, uuid[], boolean);

CREATE
OR REPLACE FUNCTION fn_category_totals(
    p_workspace_id uuid,
    p_kind text,                    -- 'INCOME' | 'EXPENSE'
    p_from timestamp,               -- the requested `from`, at 00:00
    p_until timestamp,              -- the requested `to` + 1 day
    p_account_ids uuid[],           -- NULL for all accounts
    p_include_archived boolean
) RETURNS TABLE (category_id uuid, parent_id uuid, currency char(3), total numeric(19, 4))
    LANGUAGE sql
    STABLE
    PARALLEL SAFE
AS
$$
WITH accounts AS (SELECT id, currency
                  FROM t_accounts
                  WHERE workspace_id = p_workspace_id
                    AND (p_include_archived OR NOT archived)
                    AND (p_account_ids IS NULL OR id = ANY (p_account_ids)))
-- One kind carries one sign, so ABS of the sum is the magnitude, as cashflow answers it
SELECT o.category_id,
       c.parent_id,
       a.currency,
       ABS(SUM(o.amount))::numeric(19, 4)
FROM t_operations o
         JOIN accounts a ON a.id = o.account_id
         JOIN t_categories c ON c.id = o.category_id AND c.workspace_id = p_workspace_id
WHERE o.workspace_id = p_workspace_id
  AND o.kind = p_kind
  AND o.kind <> 'TRANSFER'
  AND o.occurred_at >= p_from
  AND o.occurred_at < p_until
GROUP BY o.category_id, c.parent_id, a.currency
HAVING SUM(o.amount) <> 0
ORDER BY o.category_id, a.currency;
$$;
